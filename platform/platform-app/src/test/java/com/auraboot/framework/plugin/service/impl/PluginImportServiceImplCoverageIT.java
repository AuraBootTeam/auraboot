package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-stack coverage IT for {@link PluginImportServiceImpl} — the two-phase import
 * orchestration (parse → preview → execute with OVERWRITE), the version update-diff
 * path (re-import the same pluginId at 1.0.1), import history/status lookup, manifest
 * validation overloads, and rollback of the top version.
 *
 * <p>Fixture plugins are cross-reference-free (no command→model or menu/role dangling
 * refs), so strict mode accepts them; the strict/deferral error paths are covered by
 * {@code PluginImportTwoPhaseReferenceIntegrationTest}.
 */
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("PluginImportServiceImpl Coverage IT — two-phase import, update-diff, rollback")
class PluginImportServiceImplCoverageIT extends BaseIntegrationTest {

    private static final String PLUGIN_V1 = "plugin-test/coverage-wave3/plugin-v1";
    private static final String PLUGIN_V101 = "plugin-test/coverage-wave3/plugin-v101";
    private static final String PLUGIN_ID = "com.test.covwave3.plugin";

    @Autowired
    private PluginImportService pluginImportService;
    @Autowired
    private ObjectMapper objectMapper;

    private String dirOf(String resourcePath) throws Exception {
        var resource = getClass().getClassLoader().getResource(resourcePath);
        assertThat(resource).as("fixture dir %s must exist", resourcePath).isNotNull();
        return Paths.get(resource.toURI()).toString();
    }

    private ImportExecuteResult importDir(String resourcePath) throws Exception {
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(resourcePath), false);
        assertThat(preview.isValid()).as("preview errors: %s", preview.getErrors()).isTrue();
        ImportExecuteResult result = pluginImportService.execute(preview.getImportId(),
                com.auraboot.framework.plugin.dto.imports.ImportRequest.builder()
                        .importId(preview.getImportId())
                        .conflictStrategy(com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy.OVERWRITE)
                        .build());
        assertThat(result.isSuccess()).as("execute result: %s", result.getErrorMessage()).isTrue();
        return result;
    }

    @Test
    @DisplayName("two-phase import v1 → update to 1.0.1 → history → rollback → manifest validation")
    void importUpdateRollbackScenario() throws Exception {
        // ── v1: fresh create ──
        ImportExecuteResult v1 = importDir(PLUGIN_V1);
        assertThat(v1.getStatus().code()).isEqualToIgnoringCase("SUCCESS");
        assertThat(v1.getPluginId()).isEqualTo(PLUGIN_ID);

        // ── 1.0.1: same pluginId, extra field/permission — OVERWRITE update-diff path ──
        ImportExecuteResult v101 = importDir(PLUGIN_V101);
        assertThat(v101.isSuccess()).isTrue();
        assertThat(v101.getVersion()).isEqualTo("1.0.1");

        // ── history & status ──
        assertThat(pluginImportService.canRollback(v101.getImportId())).isTrue();
        List<PluginImportService.ImportHistoryDTO> history =
                pluginImportService.getPluginImportHistory(PLUGIN_ID);
        assertThat(history).extracting("importId").contains(v1.getImportId(), v101.getImportId());
        assertNotNull(pluginImportService.getImportStatus(v101.getImportId()));

        // ── manifest validation overloads ──
        String manifestJson = java.nio.file.Files.readString(
                java.nio.file.Paths.get(dirOf(PLUGIN_V101), "plugin.json"));
        PluginManifestExtended manifest = objectMapper.readValue(manifestJson, PluginManifestExtended.class);
        assertThat(pluginImportService.validateManifest(manifest)).isEmpty();
        var broken = new PluginManifestExtended();
        broken.setPluginId("");
        assertThat(pluginImportService.validateManifest(broken)).isNotEmpty();

        // ── rollback the 1.0.1 import: plugin returns to the v1 state ──
        ImportExecuteResult rolledBack = pluginImportService.rollback(v101.getImportId());
        assertThat(rolledBack.isSuccess()).isTrue();

        // re-import after rollback proves the tenant is still consistent for OVERWRITE
        ImportExecuteResult reImported = importDir(PLUGIN_V101);
        assertThat(reImported.isSuccess()).isTrue();
    }

    private static void assertNotNull(Object o) {
        org.assertj.core.api.Assertions.assertThat(o).isNotNull();
    }

    @Test
    @DisplayName("rollback of an unknown import fails with a clear error")
    void rollbackUnknownImport() {
        assertThatThrownBy(() -> pluginImportService.rollback("covw3-no-such-import"))
                .isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class)
                .hasMessageContaining("Import not found");
    }
}
