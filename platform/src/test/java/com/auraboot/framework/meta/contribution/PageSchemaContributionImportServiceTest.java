package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.meta.contribution.mapper.PageSchemaContributionMapper;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.plugin.dto.imports.PageContributionDefinitionDTO;
import com.auraboot.framework.plugin.exception.PluginException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageSchemaContributionImportServiceTest {

    @Mock private PageSchemaContributionMapper mapper;
    @Mock private PageSchemaService pageSchemaService;
    @Mock private EnvironmentService environmentService;

    private PageSchemaContributionImportService service;

    @BeforeEach
    void setUp() {
        service = new PageSchemaContributionImportService(
                mapper, pageSchemaService, new PageSchemaContributionComposer(), environmentService);
        MetaContext.setCurrentTenantId(41L);
        MetaContext.setEnvironmentId(73L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void reimportReactivatesMatchingRowsAndDeactivatesStaleRowsWithoutUpdatingBasePage() {
        when(pageSchemaService.findAnyByPageKey("crm-opportunity-detail")).thenReturn(targetPage());
        PersistedPageSchemaContribution retained = persisted(1L, "select-product");
        PersistedPageSchemaContribution stale = persisted(2L, "old-action");
        when(mapper.selectList(any())).thenReturn(List.of(retained, stale));

        service.replaceForPlugin("sales-plugin-pid", "2.0.0", 41L,
                List.of(definition("select-product", "select_product")));

        ArgumentCaptor<PersistedPageSchemaContribution> updates =
                ArgumentCaptor.forClass(PersistedPageSchemaContribution.class);
        verify(mapper, org.mockito.Mockito.times(3)).updateById(updates.capture());
        assertThat(stale.getActive()).isFalse();
        assertThat(retained.getActive()).isTrue();
        assertThat(retained.getPluginVersion()).isEqualTo("2.0.0");
        assertThat(retained.getPayload()).containsEntry("code", "select_product");
        verify(mapper, never()).insert(any(PersistedPageSchemaContribution.class));
        verify(pageSchemaService).findAnyByPageKey("crm-opportunity-detail");
    }

    @Test
    void missingTargetOrSlotFailsBeforeAnyPersistentMutation() {
        when(pageSchemaService.findAnyByPageKey("crm-opportunity-detail")).thenReturn(null);

        assertThatThrownBy(() -> service.replaceForPlugin(
                "sales-plugin-pid", "1.0.0", 41L, List.of(definition("select", "select"))))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Dangling page contribution target");
        verify(mapper, never()).selectList(any());
        verify(mapper, never()).updateById(any(PersistedPageSchemaContribution.class));
        verify(mapper, never()).insert(any(PersistedPageSchemaContribution.class));

        when(pageSchemaService.findAnyByPageKey("crm-opportunity-detail")).thenReturn(targetPage());
        PageContributionDefinitionDTO missingSlot = definition("bad-slot", "bad");
        missingSlot.setSlotId("not-declared");
        assertThatThrownBy(() -> service.replaceForPlugin(
                "sales-plugin-pid", "1.0.0", 41L, List.of(missingSlot)))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("missing slot");
        verify(mapper, never()).selectList(any());
    }

    @Test
    void emptyReimportDeactivatesAllPreviouslyActiveContributions() {
        PersistedPageSchemaContribution stale = persisted(2L, "old-action");
        when(mapper.selectList(any())).thenReturn(List.of(stale));

        service.replaceForPlugin("sales-plugin-pid", "2.0.0", 41L, List.of());

        assertThat(stale.getActive()).isFalse();
        verify(mapper).updateById(stale);
        verify(pageSchemaService, never()).findAnyByPageKey(any());
    }

    private PersistedPageSchemaContribution persisted(Long id, String contributionId) {
        PersistedPageSchemaContribution row = new PersistedPageSchemaContribution();
        row.setId(id);
        row.setPid("01ARZ3NDEKTSV4RRFFQ69G5FA" + id);
        row.setTenantId(41L);
        row.setEnvId(73L);
        row.setPluginPid("sales-plugin-pid");
        row.setContributionId(contributionId);
        row.setActive(true);
        row.setDeletedFlag(false);
        row.setCreatedAt(Instant.EPOCH);
        return row;
    }

    private PageContributionDefinitionDTO definition(String id, String code) {
        return PageContributionDefinitionDTO.builder()
                .id(id)
                .targetPageKey("crm-opportunity-detail")
                .slotId("product-actions")
                .kind("action")
                .priority(50)
                .payload(Map.of("code", code, "label", "Select product"))
                .build();
    }

    private PageSchemaDTO targetPage() {
        Map<String, Object> subTable = new LinkedHashMap<>();
        subTable.put("actions", new ArrayList<>(List.of(Map.of("code", "open"))));
        Map<String, Object> productLines = new LinkedHashMap<>();
        productLines.put("id", "opportunity-lines");
        productLines.put("blockType", "sub-table");
        productLines.put("subTable", subTable);
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("crm-opportunity-detail");
        page.setBlocks(new ArrayList<>(List.of(productLines)));
        page.setExtension(Map.of(PageSchemaContributionComposer.SLOTS_EXTENSION_KEY, List.of(Map.of(
                "id", "product-actions",
                "kind", "action",
                "anchor", Map.of("target", "sub-table-actions", "blockId", "opportunity-lines")))));
        return page;
    }
}
