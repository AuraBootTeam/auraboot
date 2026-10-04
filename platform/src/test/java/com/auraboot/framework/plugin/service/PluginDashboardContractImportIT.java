package com.auraboot.framework.plugin.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.service.impl.PluginResourceImporter;
import com.auraboot.framework.plugin.service.impl.PluginDirectoryLoader;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the first-class {@code config/dashboards/*.json} import contract (Plan #8).
 *
 * <p>Validates that:
 * <ol>
 *   <li>The directory loader picks up {@code config/dashboards/} when declared in {@code resourceDirs}</li>
 *   <li>{@link PluginResourceImporter#importDashboard} persists the dashboard to {@code ab_dashboard}</li>
 *   <li>Widgets are stored with correct count and shape</li>
 *   <li>OVERWRITE strategy updates an existing dashboard</li>
 *   <li>SKIP strategy leaves an existing dashboard unchanged</li>
 * </ol>
 *
 * Uses real PostgreSQL; extends {@link BaseIntegrationTest} for MetaContext + {@code @Transactional} rollback.
 */
@org.springframework.context.annotation.Import(PluginDashboardContractImportIT.StorefrontProfileConfiguration.class)
class PluginDashboardContractImportIT extends BaseIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class StorefrontProfileConfiguration {
        @org.springframework.context.annotation.Bean
        com.auraboot.framework.plugin.validation.PageSchemaRenderProfile storefrontProfile() {
            return new com.auraboot.framework.plugin.validation.PageSchemaRenderProfile("storefront",
                    java.util.Set.of("plp"),
                    java.util.Set.of("search-bar", "facet-filter", "product-grid", "pagination"));
        }
    }

    @Autowired
    private PluginImportService importService;

    @Autowired
    private com.auraboot.framework.meta.service.PageSchemaService pages;

    @Test
    void registeredStorefrontProfileImportsIntoCanonicalPageStorageAndRejectsProfileEscape() {
        String code = "commerce_plp_" + com.auraboot.framework.common.util.UniqueIdGenerator.generate().toLowerCase();
        var page = new com.auraboot.framework.plugin.dto.imports.PageSchemaDTO();
        page.setPageKey(code);
        page.setKind("plp");
        page.setProfile("storefront");
        page.setSchemaVersion(4);
        page.setTitle(Map.of("zh-CN", "商品列表", "en", "Products"));
        page.setLayout(Map.of("type", "stack"));
        page.setBlocks(List.of(
                Map.of("id", "search", "blockType", "search-bar"),
                Map.of("id", "facets", "blockType", "facet-filter"),
                Map.of("id", "products", "blockType", "product-grid"),
                Map.of("id", "pages", "blockType", "pagination")));
        var manifest = PluginManifestExtended.builder().pluginId("it.commerce." + code)
                .namespace("commerce").version("1.0.0").pages(List.of(page)).build();
        var imported = importService.executeFromManifest(manifest, new ImportRequest());
        assertThat(imported.isSuccess()).as("actual import result: %s", imported.getErrorMessage()).isTrue();
        var stored = pages.findByPageKey(code);
        assertThat(stored).as("canonical runtime read must resolve the imported page").isNotNull();
        assertThat(stored.getKind()).isEqualTo("plp");
        assertThat(stored.getProfile()).isEqualTo("storefront");
        assertThat(stored.getSchemaVersion()).isEqualTo(4);
        assertThat(stored.getBlocks()).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(System.getenv("POSTGRES_DB"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_page_schema WHERE tenant_id=? AND page_key=? "
                        + "AND kind='plp' AND profile='storefront' AND status='published' AND is_current=true",
                Integer.class, getTestTenant().getId(), code)).isEqualTo(1);

        String deniedKey = code + "_denied";
        page.setPageKey(deniedKey);
        page.setProfile("admin");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> importService.executeFromManifest(manifest, new ImportRequest()))
                .isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class)
                .hasMessageContaining("S-PAGE-KIND-UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_page_schema WHERE tenant_id=? AND page_key=?",
                Integer.class, getTestTenant().getId(), deniedKey)).isZero();
        assertThat(pages.findByPageKey(code).getBlocks()).hasSize(4);
    }

    @Autowired
    private PluginResourceImporter importer;

    @Autowired
    private PluginDirectoryLoader directoryLoader;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ helper

    private String uniqueCode() {
        return "tdc_it_" + System.currentTimeMillis();
    }

    private DashboardDefinitionDTO buildDto(String code, int widgetCount) {
        List<Object> widgets = new java.util.ArrayList<>();
        for (int i = 0; i < widgetCount; i++) {
            widgets.add(Map.of(
                    "id", "w" + i,
                    "type", "smart-bar-chart",
                    "x", 0,
                    "y", i * 3,
                    "w", 12,
                    "h", 3,
                    "title", "Widget " + i,
                    "config", Map.of("title", "Widget " + i)
            ));
        }
        return DashboardDefinitionDTO.builder()
                .code(code)
                .title("IT Dashboard " + code)
                .description("Integration test fixture")
                .scope("global")
                .status("published")
                .layoutConfig(Map.of("columns", 12, "rowHeight", 100, "gap", 16))
                .extension(Map.of("workbenchContribution", Map.of("enabled", true)))
                .widgets(widgets)
                .build();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("config/dashboards/*.json fixture is loaded by PluginDirectoryLoader")
    void directoryLoader_readsFixtureDashboards() throws Exception {
        URL resource = getClass().getClassLoader()
                .getResource("plugin-test/dashboard-contract-plugin");
        assertThat(resource).as("fixture plugin dir must exist in test resources").isNotNull();

        PluginManifestExtended manifest = directoryLoader.loadFromDirectory(Paths.get(resource.toURI()));

        assertThat(manifest.getDashboards())
                .as("manifest must contain the fixture dashboard")
                .isNotNull()
                .hasSize(1);

        DashboardDefinitionDTO loaded = manifest.getDashboards().get(0);
        assertThat(loaded.getCode()).isEqualTo("tdc_manual_dashboard");
        assertThat(loaded.getTitle()).isEqualTo("Test Manual Dashboard");
        assertThat(loaded.getWidgets()).hasSize(2);
        assertThat(loaded.isValid()).isTrue();
    }

    @Test
    @DisplayName("importDashboard persists a new dashboard to ab_dashboard")
    void importDashboard_createsAbDashboardRow() {
        String code = uniqueCode();
        Long tenantId = getTestTenant().getId();
        DashboardDefinitionDTO dto = buildDto(code, 2);

        PluginResource resource = importer.importDashboard(dto, "test-pid", "test-import-id",
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(resource).isNotNull();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_dashboard WHERE code = ? AND tenant_id = ? AND deleted_flag = FALSE",
                Integer.class, code, tenantId);
        assertThat(count).as("ab_dashboard must have exactly 1 row for code=%s", code).isEqualTo(1);
    }

    @Test
    @DisplayName("importDashboard stores correct widget count in ab_dashboard.widgets JSONB")
    void importDashboard_storesCorrectWidgets() {
        String code = uniqueCode();
        Long tenantId = getTestTenant().getId();
        DashboardDefinitionDTO dto = buildDto(code, 3);

        importer.importDashboard(dto, "test-pid", "test-import-id",
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        Integer widgetCount = jdbc.queryForObject(
                "SELECT jsonb_array_length(widgets) FROM ab_dashboard WHERE code = ? AND tenant_id = ? AND deleted_flag = FALSE",
                Integer.class, code, tenantId);
        assertThat(widgetCount).as("widgets JSONB array must contain all 3 widgets").isEqualTo(3);
    }

    @Test
    @DisplayName("importDashboard stores workbench contribution extension")
    void importDashboard_storesWorkbenchContributionExtension() {
        String code = uniqueCode();
        Long tenantId = getTestTenant().getId();

        importer.importDashboard(buildDto(code, 1), "test-pid", "test-import-id",
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        Boolean enabled = jdbc.queryForObject(
                "SELECT (extension #>> '{workbenchContribution,enabled}')::boolean "
                        + "FROM ab_dashboard WHERE code = ? AND tenant_id = ? AND deleted_flag = FALSE",
                Boolean.class, code, tenantId);
        assertThat(enabled).isTrue();
    }

    @Test
    @DisplayName("importDashboard with OVERWRITE updates existing dashboard widgets")
    void importDashboard_overwriteUpdatesWidgets() {
        String code = uniqueCode();
        Long tenantId = getTestTenant().getId();

        // First import — 1 widget
        importer.importDashboard(buildDto(code, 1), "test-pid", "import-1",
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        // Second import — OVERWRITE with 4 widgets
        importer.importDashboard(buildDto(code, 4), "test-pid", "import-2",
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        Integer widgetCount = jdbc.queryForObject(
                "SELECT jsonb_array_length(widgets) FROM ab_dashboard WHERE code = ? AND tenant_id = ? AND deleted_flag = FALSE",
                Integer.class, code, tenantId);
        assertThat(widgetCount).as("OVERWRITE must update widget count to 4").isEqualTo(4);
    }

    @Test
    @DisplayName("importDashboard with SKIP leaves existing dashboard unchanged")
    void importDashboard_skipLeavesExistingUnchanged() {
        String code = uniqueCode();
        Long tenantId = getTestTenant().getId();

        // First import — 1 widget
        importer.importDashboard(buildDto(code, 1), "test-pid", "import-1",
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        // Second import — SKIP with 5 widgets
        PluginResource skipResult = importer.importDashboard(buildDto(code, 5), "test-pid", "import-2",
                tenantId, ImportRequest.ConflictStrategy.SKIP);

        assertThat(skipResult).isNotNull();

        Integer widgetCount = jdbc.queryForObject(
                "SELECT jsonb_array_length(widgets) FROM ab_dashboard WHERE code = ? AND tenant_id = ? AND deleted_flag = FALSE",
                Integer.class, code, tenantId);
        assertThat(widgetCount).as("SKIP must leave widget count at 1 (unchanged)").isEqualTo(1);
    }

    @Test
    @DisplayName("importDashboard does NOT write anything to ab_page_schema")
    void importDashboard_doesNotTouchPageSchema() {
        String code = uniqueCode();
        Long tenantId = getTestTenant().getId();

        importer.importDashboard(buildDto(code, 1), "test-pid", "test-import-id",
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        Integer pageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_page_schema WHERE page_key = ? AND tenant_id = ?",
                Integer.class, code, tenantId);
        assertThat(pageCount)
                .as("config/dashboards/ import must NOT write to ab_page_schema").isEqualTo(0);
    }

    @Test
    @DisplayName("DTO validation rejects missing required fields")
    void dashboardDefinitionDTO_validationRejectsIncomplete() {
        DashboardDefinitionDTO noCode = DashboardDefinitionDTO.builder()
                .title("No Code Dashboard")
                .widgets(List.of(Map.of("id", "w1")))
                .build();
        assertThat(noCode.isValid()).isFalse();

        DashboardDefinitionDTO noTitle = DashboardDefinitionDTO.builder()
                .code("no_title_dash")
                .widgets(List.of(Map.of("id", "w1")))
                .build();
        assertThat(noTitle.isValid()).isFalse();

        DashboardDefinitionDTO noWidgets = DashboardDefinitionDTO.builder()
                .code("no_widgets_dash")
                .title("No Widgets")
                .widgets(List.of())
                .build();
        assertThat(noWidgets.isValid()).isFalse();
    }
}
