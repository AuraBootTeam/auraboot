package com.auraboot.framework.view;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.view.dto.AutoSaveViewRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.dto.SavedViewUpdateRequest;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.entity.ViewConfig.ColumnConfig;
import com.auraboot.framework.view.entity.ViewConfig.SortConfig;
import com.auraboot.framework.view.entity.ViewConfig.ToolbarActionConfig;
import com.auraboot.framework.view.service.SavedViewService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real-stack contract for runtime personalization as a SavedView overlay. */
class SavedViewPersonalizationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SavedViewService savedViewService;

    @Autowired
    private PageSchemaService pageSchemaService;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void personalOverlayIsIsolatedReplayedAndRepairedWithoutAuthoringMutation() {
        String suffix = Long.toString(System.nanoTime());
        String modelCode = "overlay_model_" + suffix;
        String pageKey = "overlay_page_" + suffix;
        PageSchema page = insertPublishedPage(pageKey, modelCode, baselineBlocks(true, true));
        long changeSetsBefore = authoringChangeSetCount();

        ViewConfig firstPreference = preference(false, true);
        SavedViewDTO first = savedViewService.autoSave(autoSave(modelCode, pageKey, firstPreference));

        assertThat(first.getViewConfig().getMeta().getOverlayStatus()).isEqualTo("CURRENT");
        assertThat(first.getViewConfig().getMeta().getBasePagePid()).isEqualTo(page.getPid());
        assertThat(first.getViewConfig().getMeta().getBaseFieldCodes())
                .containsExactly("mandatory", "optional");
        assertThat(authoringChangeSetCount()).isEqualTo(changeSetsBefore);

        MetaContext.Snapshot originalContext = MetaContext.snapshot();
        String secondUserPid = UniqueIdGenerator.generate();
        try {
            MetaContext.setContext(
                    getTestTenant().getId(), getTestUser().getId(), secondUserPid, "overlay-user");
            MetaContext.setMemberId(getTestTenantMember().getId());
            MetaContext.setEnvironmentId(originalContext.envId());

            assertThat(savedViewService.getPersonalViews(modelCode, pageKey)).isEmpty();
            SavedViewDTO second = savedViewService.autoSave(
                    autoSave(modelCode, pageKey, preference(true, true)));
            assertThat(second.getOwnerId()).isEqualTo(secondUserPid);
            assertThat(second.getPid()).isNotEqualTo(first.getPid());
            assertThat(second.getViewConfig().getColumns())
                    .anySatisfy(column -> {
                        assertThat(column.getFieldCode()).isEqualTo("optional");
                        assertThat(column.getVisible()).isTrue();
                    });
        } finally {
            MetaContext.restore(originalContext);
        }

        assertThat(savedViewService.getPersonalViews(modelCode, pageKey))
                .extracting(SavedViewDTO::getPid)
                .containsExactly(first.getPid());

        ViewConfig invalid = preference(false, false);
        SavedViewUpdateRequest rejectedUpdate = new SavedViewUpdateRequest();
        rejectedUpdate.setViewConfig(invalid);
        assertThatThrownBy(() -> savedViewService.update(first.getPid(), rejectedUpdate))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("view.overlay.mandatory-cannot-hide:action:export");
        assertThat(savedViewService.findByPid(first.getPid()).getViewConfig()
                .getToolbarActions().get(0).getVisible()).isTrue();
        assertThat(authoringChangeSetCount()).isEqualTo(changeSetsBefore);

        updatePublishedPage(page, baselineBlocks(false, false));

        PageSchemaDTO runtimePage = pageSchemaService.findByPageKey(pageKey);
        SavedViewDTO stale = savedViewService.findByPid(first.getPid());
        assertThat(runtimePage).isNotNull();
        assertThat(runtimePage.getBlocks()).isNotEmpty();
        assertThat(stale.getViewConfig().getMeta().getOverlayStatus()).isEqualTo("STALE");
        assertThat(stale.getViewConfig().getMeta().getOverlayReasonCodes())
                .contains("FIELD_REMOVED", "ACTION_REMOVED");
        assertThat(stale.getViewConfig().getColumns())
                .extracting(ColumnConfig::getFieldCode)
                .containsExactly("mandatory");
        assertThat(stale.getViewConfig().getSorts()).isEmpty();
        assertThat(stale.getViewConfig().getToolbarActions()).isEmpty();
        assertThat(authoringChangeSetCount()).isEqualTo(changeSetsBefore);

        SavedViewUpdateRequest repair = new SavedViewUpdateRequest();
        repair.setViewConfig(stale.getViewConfig());
        SavedViewDTO repaired = savedViewService.update(first.getPid(), repair);

        assertThat(repaired.getViewConfig().getMeta().getOverlayStatus()).isEqualTo("CURRENT");
        assertThat(repaired.getViewConfig().getMeta().getOverlayReasonCodes()).isEmpty();
        assertThat(repaired.getViewConfig().getColumns())
                .extracting(ColumnConfig::getFieldCode)
                .containsExactly("mandatory");
        assertThat(authoringChangeSetCount()).isEqualTo(changeSetsBefore);
    }

    private PageSchema insertPublishedPage(
            String pageKey,
            String modelCode,
            List<Map<String, Object>> blocks) {
        PageSchema page = new PageSchema();
        page.setPid(UniqueIdGenerator.generate());
        page.setTenantId(getTestTenant().getId());
        page.setPageKey(pageKey);
        page.setModelCode(modelCode);
        page.setName(pageKey);
        page.setKind("list");
        page.setSchemaVersion(4);
        page.setProfile("admin");
        page.setTitle("{\"zh-CN\":\"个性化覆盖层测试\"}");
        page.setLayout("{\"type\":\"stack\"}");
        page.setBlocks(json(blocks));
        page.setStatus("published");
        page.setPublishedAt(Instant.now());
        page.setVersion(1);
        page.setSemver("1.0.0");
        page.setRowVersion(1);
        page.setIsCurrent(true);
        page.setIsTemplate(false);
        page.setSortWeight(0);
        page.setDeletedFlag(false);
        page.setCreatedAt(Instant.now());
        page.setUpdatedAt(Instant.now());
        assertThat(pageSchemaMapper.insert(page)).isEqualTo(1);
        return page;
    }

    private void updatePublishedPage(PageSchema page, List<Map<String, Object>> blocks) {
        page.setBlocks(json(blocks));
        page.setRowVersion(page.getRowVersion() + 1);
        page.setUpdatedAt(Instant.now());
        assertThat(pageSchemaMapper.updateById(page)).isEqualTo(1);
    }

    private List<Map<String, Object>> baselineBlocks(
            boolean includeOptional,
            boolean includeExport) {
        List<Map<String, Object>> columns = new java.util.ArrayList<>();
        columns.add(Map.of("field", "mandatory", "label", "单号", "mandatory", true));
        if (includeOptional) {
            columns.add(Map.of("field", "optional", "label", "备注", "required", true));
        }
        List<Map<String, Object>> buttons = new java.util.ArrayList<>();
        if (includeExport) {
            buttons.add(Map.of("code", "export", "label", "导出", "mandatory", true));
        }
        return List.of(
                Map.of("id", "table", "blockType", "table", "columns", columns),
                Map.of("id", "toolbar", "blockType", "toolbar", "buttons", buttons));
    }

    private ViewConfig preference(boolean optionalVisible, boolean exportVisible) {
        ViewConfig config = new ViewConfig();
        config.setColumns(List.of(
                ColumnConfig.builder().fieldCode("mandatory").visible(true).order(0).build(),
                ColumnConfig.builder().fieldCode("optional").visible(optionalVisible).order(1).build()));
        config.setSorts(List.of(
                SortConfig.builder().fieldCode("optional").direction("asc").priority(0).build()));
        config.setToolbarActions(List.of(
                ToolbarActionConfig.builder()
                        .code("export").visible(exportVisible).pinned(true).order(0).build()));
        return config;
    }

    private AutoSaveViewRequest autoSave(String modelCode, String pageKey, ViewConfig config) {
        AutoSaveViewRequest request = new AutoSaveViewRequest();
        request.setModelCode(modelCode);
        request.setPageKey(pageKey);
        request.setViewConfig(config);
        return request;
    }

    private long authoringChangeSetCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_authoring_change_set WHERE tenant_id = ? AND env_id = ?",
                Long.class,
                getTestTenant().getId(),
                MetaContext.getCurrentEnvironmentId());
        return count == null ? 0 : count;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
