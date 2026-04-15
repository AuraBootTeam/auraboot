package com.auraboot.framework.plugin.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.service.impl.PluginResourceImporterImpl;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving that plugin pages with kind=dashboard are routed to
 * ab_dashboard (via DashboardService) and NOT written to ab_page_schema.
 *
 * Uses real PostgreSQL; extends BaseIntegrationTest for MetaContext + @Transactional rollback.
 */
class PluginDashboardImportIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PluginResourceImporterImpl importer;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("kind=dashboard plugin page is converted and persisted to ab_dashboard only")
    void kindDashboardPage_importsToDashboardTable() {
        // Use unique code per run to avoid cross-test collisions inside the same transaction
        String code = "it_dashboard_" + System.currentTimeMillis();

        PageSchemaDTO dto = PageSchemaDTO.builder()
                .pageKey(code)
                .kind("dashboard")
                .name("IT Dashboard")
                .nameZhCN("集成测试仪表板")
                .layout(Map.of("type", "grid", "cols", 12))
                .blocks(List.of(
                        Map.of("id", "b1", "blockType", "chart", "chartType", "bar",
                               "title", "Bar Chart",
                               "colSpan", 6, "rowSpan", 3),
                        Map.of("id", "b2", "blockType", "stat-card",
                               "title", "Stat Card",
                               "colSpan", 12, "rowSpan", 1),
                        Map.of("id", "b3", "blockType", "table",
                               "title", "Data Table",
                               "colSpan", 12, "rowSpan", 4)
                ))
                .build();

        // importPage(dto, pluginPid, importId, tenantId, conflictStrategy, autoPublish)
        Long tenantId = getTestTenant().getId();
        PluginResource resource = importer.importPage(
                dto,
                "test-plugin-pid",
                "test-import-id",
                tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE,
                Boolean.TRUE
        );

        assertThat(resource).isNotNull();

        // Assert: row exists in ab_dashboard for this code + tenant
        Integer dashCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_dashboard WHERE code = ? AND tenant_id = ? AND deleted_flag = FALSE",
                Integer.class, code, tenantId);
        assertThat(dashCount).as("ab_dashboard should have exactly 1 row for code=%s", code)
                .isEqualTo(1);

        // Assert: NO row in ab_page_schema for this pageKey
        Integer pageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_page_schema WHERE page_key = ? AND tenant_id = ?",
                Integer.class, code, tenantId);
        assertThat(pageCount).as("ab_page_schema must NOT have a row for code=%s (kind=dashboard bypasses it)", code)
                .isEqualTo(0);

        // Assert: widgets JSONB has the 3 converted blocks
        Integer widgetCount = jdbc.queryForObject(
                "SELECT jsonb_array_length(widgets) FROM ab_dashboard WHERE code = ? AND tenant_id = ? AND deleted_flag = FALSE",
                Integer.class, code, tenantId);
        assertThat(widgetCount).as("widgets array should contain all 3 converted blocks").isEqualTo(3);
    }

    @Test
    @DisplayName("kind=dashboard with SKIP strategy does not re-import an existing dashboard")
    void kindDashboardPage_skipStrategy_doesNotOverwrite() {
        String code = "it_dashboard_skip_" + System.currentTimeMillis();
        Long tenantId = getTestTenant().getId();

        PageSchemaDTO dto = PageSchemaDTO.builder()
                .pageKey(code)
                .kind("dashboard")
                .name("Skip Test Dashboard")
                .blocks(List.of(
                        Map.of("id", "b1", "blockType", "chart", "chartType", "bar",
                               "title", "Bar Chart", "colSpan", 12, "rowSpan", 3)
                ))
                .build();

        // First import — creates the row
        importer.importPage(dto, "test-plugin-pid", "test-import-id-1",
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE, Boolean.TRUE);

        // Second import with SKIP — should return SKIP action, not throw
        PageSchemaDTO dtoV2 = PageSchemaDTO.builder()
                .pageKey(code)
                .kind("dashboard")
                .name("Updated Dashboard")
                .blocks(List.of(
                        Map.of("id", "b1", "blockType", "chart",
                               "title", "Bar Chart", "colSpan", 12, "rowSpan", 3),
                        Map.of("id", "b2", "blockType", "stat-card",
                               "title", "Stat Card", "colSpan", 6, "rowSpan", 1)
                ))
                .build();

        PluginResource skipResult = importer.importPage(dtoV2, "test-plugin-pid", "test-import-id-2",
                tenantId, ImportRequest.ConflictStrategy.SKIP, Boolean.TRUE);

        assertThat(skipResult).isNotNull();

        // Original 1-widget dashboard must be unchanged (not updated to 2 widgets)
        Integer widgetCount = jdbc.queryForObject(
                "SELECT jsonb_array_length(widgets) FROM ab_dashboard WHERE code = ? AND tenant_id = ? AND deleted_flag = FALSE",
                Integer.class, code, tenantId);
        assertThat(widgetCount).as("SKIP strategy must leave widgets count unchanged at 1").isEqualTo(1);
    }
}
