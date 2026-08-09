package com.auraboot.framework.view.service.impl;

import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaRuntimeDTO;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.entity.ViewConfig.ColumnConfig;
import com.auraboot.framework.view.entity.ViewConfig.FilterConfig;
import com.auraboot.framework.view.entity.ViewConfig.SortConfig;
import com.auraboot.framework.view.entity.ViewConfig.ToolbarActionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewOverlayPolicyTest {

    @Mock
    private PageSchemaService pageSchemaService;

    private SavedViewOverlayPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new SavedViewOverlayPolicy(
                pageSchemaService, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void stampsServerLineageAndRejectsMandatoryHiding() {
        when(pageSchemaService.findByPageKey("orders"))
                .thenReturn(page("release-1", 4, "checksum-1", true, true));
        ViewConfig valid = new ViewConfig();
        valid.setColumns(List.of(column("optional", false)));

        ViewConfig stamped = policy.validateAndStamp("orders", valid);

        assertThat(stamped.getMeta().getOverlayStatus()).isEqualTo("CURRENT");
        assertThat(stamped.getMeta().getBasePagePid()).isEqualTo("page-orders");
        assertThat(stamped.getMeta().getBaseReleasePid()).isEqualTo("release-1");
        assertThat(stamped.getMeta().getBaseChannelVersion()).isEqualTo(4);
        assertThat(stamped.getMeta().getBaseFieldCodes())
                .containsExactly("mandatory", "optional");
        assertThat(stamped.getMeta().getBaseActionCodes()).containsExactly("export");

        ViewConfig hiddenField = new ViewConfig();
        hiddenField.setColumns(List.of(column("mandatory", false)));
        assertThatThrownBy(() -> policy.validateAndStamp("orders", hiddenField))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("view.overlay.mandatory-cannot-hide:field:mandatory");

        ViewConfig hiddenAction = new ViewConfig();
        hiddenAction.setToolbarActions(List.of(action("export", false)));
        assertThatThrownBy(() -> policy.validateAndStamp("orders", hiddenAction))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("view.overlay.mandatory-cannot-hide:action:export");
    }

    @Test
    void staleReplayDropsDeletedReferencesAndRestoresMandatoryElements() {
        when(pageSchemaService.findByPageKey("orders"))
                .thenReturn(page("release-1", 1, "checksum-1", true, true));
        ViewConfig stored = new ViewConfig();
        stored.setColumns(List.of(column("mandatory", true), column("optional", false)));
        stored.setSorts(List.of(sort("optional")));
        stored.setFilters(List.of(filter("optional")));
        stored.setToolbarActions(List.of(action("export", true)));
        stored = policy.validateAndStamp("orders", stored);
        stored.getColumns().get(0).setVisible(false);

        when(pageSchemaService.findByPageKey("orders"))
                .thenReturn(page("release-2", 2, "checksum-2", false, false));
        ViewConfig replayed = policy.replay("orders", stored);

        assertThat(replayed.getMeta().getOverlayStatus()).isEqualTo("STALE");
        assertThat(replayed.getMeta().getOverlayReasonCodes())
                .contains("FIELD_REMOVED", "ACTION_REMOVED", "MANDATORY_ELEMENT_RESTORED");
        assertThat(replayed.getColumns())
                .singleElement()
                .satisfies(column -> {
                    assertThat(column.getFieldCode()).isEqualTo("mandatory");
                    assertThat(column.getVisible()).isTrue();
                });
        assertThat(replayed.getSorts()).isEmpty();
        assertThat(replayed.getFilters()).isEmpty();
        assertThat(replayed.getToolbarActions()).isEmpty();
        assertThat(replayed.getMeta().getOverlayStalePaths())
                .contains("/columns/optional", "/toolbarActions/export");
    }

    @Test
    void compatibleReleaseUpgradeRebasesWithoutCallingItStale() {
        when(pageSchemaService.findByPageKey("orders"))
                .thenReturn(page("release-1", 1, "checksum-1", true, true));
        ViewConfig stored = policy.validateAndStamp("orders", new ViewConfig());

        when(pageSchemaService.findByPageKey("orders"))
                .thenReturn(page("release-2", 2, "checksum-2", true, true));
        ViewConfig replayed = policy.replay("orders", stored);

        assertThat(replayed.getMeta().getOverlayStatus()).isEqualTo("REBASED");
        assertThat(replayed.getMeta().getOverlayReasonCodes())
                .containsExactly("BASE_RELEASE_CHANGED");
        assertThat(replayed.getMeta().getOverlayStalePaths()).isEmpty();
    }

    @Test
    void legacyOverlayRemainsLoadableAndIsExplicitlyUntracked() {
        when(pageSchemaService.findByPageKey("orders"))
                .thenReturn(page("release-1", 1, "checksum-1", true, true));

        ViewConfig replayed = policy.replay("orders", new ViewConfig());

        assertThat(replayed.getMeta().getOverlayStatus()).isEqualTo("UNTRACKED");
        assertThat(replayed.getMeta().getOverlayReasonCodes())
                .containsExactly("LEGACY_OVERLAY_NO_LINEAGE");
    }

    private PageSchemaDTO page(
            String releasePid,
            long channelVersion,
            String checksum,
            boolean includeOptional,
            boolean includeExport) {
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPid("page-orders");
        page.setPageKey("orders");
        page.setModelCode("orders");
        page.setRowVersion(1);
        List<Map<String, Object>> columns = new java.util.ArrayList<>();
        columns.add(Map.of("field", "mandatory", "mandatory", true));
        if (includeOptional) {
            columns.add(Map.of("field", "optional"));
        }
        List<Map<String, Object>> buttons = new java.util.ArrayList<>();
        if (includeExport) {
            buttons.add(Map.of("code", "export", "mandatory", true));
        }
        page.setBlocks(List.of(
                Map.of("id", "table", "blockType", "table", "columns", columns),
                Map.of("id", "toolbar", "blockType", "toolbar", "buttons", buttons)));
        page.setRuntime(new PageSchemaRuntimeDTO(
                "AUTHORING_RELEASE", releasePid, channelVersion, 1, checksum,
                "authoring-release:" + releasePid));
        return page;
    }

    private ColumnConfig column(String fieldCode, boolean visible) {
        return ColumnConfig.builder().fieldCode(fieldCode).visible(visible).build();
    }

    private SortConfig sort(String fieldCode) {
        return SortConfig.builder().fieldCode(fieldCode).direction("asc").build();
    }

    private FilterConfig filter(String fieldCode) {
        return FilterConfig.builder().fieldCode(fieldCode).operator("eq").value("x").build();
    }

    private ToolbarActionConfig action(String code, boolean visible) {
        return ToolbarActionConfig.builder()
                .code(code).visible(visible).pinned(true).order(0).build();
    }
}
