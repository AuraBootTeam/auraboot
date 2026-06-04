package com.auraboot.framework.plugin.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the two-phase cross-plugin reference fix against a real PostgreSQL DB.
 *
 * <p>Reproduces the crm↔sales cold-reset cycle with two fixture plugins whose commands reference
 * each other's model, plus a dangling fixture. Proves end-to-end (parse → execute → sweep):
 * <ol>
 *   <li>The default (strict) mode still hard-errors on a cross-plugin command→model reference —
 *       the regression guard proving the fix did not silently weaken validation.</li>
 *   <li>With {@code deferReferenceValidation}, a cyclic plugin pair imports successfully even though
 *       no per-plugin order satisfies the strict rule, and the closing sweep then finds the cyclic
 *       references intact.</li>
 *   <li>A command referencing a model no plugin provides still imports under deferral but is flagged
 *       by {@link PluginImportService#verifyImportReferenceIntegrity()}.</li>
 * </ol>
 *
 * Uses real PostgreSQL (no H2/mock); extends {@link BaseIntegrationTest} for MetaContext + tenant.
 */
class PluginImportTwoPhaseReferenceIntegrationTest extends BaseIntegrationTest {

    private static final String PLUGIN_A = "plugin-test/twophase-cycle/plugin-a";
    private static final String PLUGIN_B = "plugin-test/twophase-cycle/plugin-b";
    private static final String PLUGIN_C_DANGLING = "plugin-test/twophase-cycle/plugin-c-dangling";

    @Autowired
    private PluginImportService pluginImportService;

    private String dirOf(String resourcePath) throws Exception {
        URL resource = getClass().getClassLoader().getResource(resourcePath);
        assertThat(resource).as("fixture dir %s must exist in test resources", resourcePath).isNotNull();
        return Paths.get(resource.toURI()).toString();
    }

    private ImportExecuteResult importDir(String resourcePath, boolean defer) throws Exception {
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(resourcePath), defer);
        assertThat(preview.isValid())
                .as("preview must be valid (defer=%s) but had errors: %s", defer, preview.getErrors())
                .isTrue();
        return pluginImportService.execute(preview.getImportId(),
                ImportRequest.builder()
                        .importId(preview.getImportId())
                        .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                        .build());
    }

    @Test
    @DisplayName("strict mode hard-errors on a command->model reference no plugin provides (regression guard)")
    void strictMode_unresolvableCommandModel_isHardError() throws Exception {
        // tpc:dangling references tp_nonexistent_model, which NO fixture ever creates — so this is a
        // hard error in strict mode regardless of what else has been imported into the test tenant.
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(PLUGIN_C_DANGLING), false);

        assertThat(preview.isValid())
                .as("strict mode must reject an unresolvable cross-plugin reference")
                .isFalse();
        assertThat(preview.getErrors())
                .anyMatch(e -> e.contains("tpc:dangling") && e.contains("tp_nonexistent_model"));
    }

    @Test
    @DisplayName("deferral imports a cyclic plugin pair; closing sweep finds the cyclic refs intact")
    void deferral_importsCyclicPair_sweepFindsRefsIntact() throws Exception {
        // plugin-a's command references tp_b_model (owned by B, not yet imported): allowed by deferral.
        ImportExecuteResult a = importDir(PLUGIN_A, true);
        assertThat(a.isSuccess()).as("plugin-a import failed: %s", a.getErrorMessage()).isTrue();

        // plugin-b's command references tp_a_model, now provided by A.
        ImportExecuteResult b = importDir(PLUGIN_B, true);
        assertThat(b.isSuccess()).as("plugin-b import failed: %s", b.getErrorMessage()).isTrue();

        // Both tp_a_model and tp_b_model exist now → the cyclic refs resolve. (Residue-tolerant:
        // asserts only that the two cyclic commands are not flagged, not that the list is empty.)
        List<String> dangling = pluginImportService.verifyImportReferenceIntegrity();
        assertThat(dangling)
                .as("cyclic refs must resolve once both sides are imported, but got: %s", dangling)
                .noneMatch(s -> s.contains("tpa:use_b"))
                .noneMatch(s -> s.contains("tpb:use_a"));
    }

    @Test
    @DisplayName("closing sweep flags a command referencing a model no plugin provides")
    void deferral_danglingReference_flaggedBySweep() throws Exception {
        // Deferral lets the dangling plugin import even though tp_nonexistent_model exists nowhere.
        ImportExecuteResult c = importDir(PLUGIN_C_DANGLING, true);
        assertThat(c.isSuccess()).as("plugin-c (deferred) import failed: %s", c.getErrorMessage()).isTrue();

        // The closing sweep re-enforces integrity: the never-provided reference must surface.
        List<String> dangling = pluginImportService.verifyImportReferenceIntegrity();
        assertThat(dangling)
                .as("sweep must flag tpc:dangling -> tp_nonexistent_model, got: %s", dangling)
                .anyMatch(s -> s.contains("tpc:dangling") && s.contains("tp_nonexistent_model"));
    }
}
