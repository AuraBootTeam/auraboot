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

    /**
     * Regression guard for the pcba-solution ↔ pcba-compliance ↔ pcba-manufacturing cyclic fix:
     * strict mode must still hard-error on menu->parent-menu and role->permission references no
     * plugin provides (tpc_dangling_menu -> tp_nonexistent_menu, tpc_dangling_role ->
     * tp_nonexistent_permission), proving deferral did not silently weaken these checks either.
     */
    @Test
    @DisplayName("strict mode hard-errors on menu->parent-menu and role->permission references no plugin provides")
    void strictMode_unresolvableMenuParentAndRolePermission_isHardError() throws Exception {
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(PLUGIN_C_DANGLING), false);

        assertThat(preview.isValid())
                .as("strict mode must reject unresolvable cross-plugin menu/role references")
                .isFalse();
        assertThat(preview.getErrors())
                .anyMatch(e -> e.contains("tpc_dangling_menu") && e.contains("tp_nonexistent_menu"));
        assertThat(preview.getErrors())
                .anyMatch(e -> e.contains("tpc_dangling_role") && e.contains("tp_nonexistent_permission"));
    }

    @Test
    @DisplayName("deferral imports a cyclic plugin pair; closing sweep finds the cyclic refs intact")
    void deferral_importsCyclicPair_sweepFindsRefsIntact() throws Exception {
        // plugin-a's command references tp_b_model (owned by B, not yet imported), and its menu
        // references B's not-yet-imported parent menu: allowed by deferral.
        ImportExecuteResult a = importDir(PLUGIN_A, true);
        assertThat(a.isSuccess()).as("plugin-a import failed: %s", a.getErrorMessage()).isTrue();

        // plugin-b's command references tp_a_model, now provided by A; plugin-b's role references
        // A's permission tpa:view, now provided by A.
        ImportExecuteResult b = importDir(PLUGIN_B, true);
        assertThat(b.isSuccess()).as("plugin-b import failed: %s", b.getErrorMessage()).isTrue();

        // Both tp_a_model and tp_b_model exist now → the cyclic command/model refs resolve. Both
        // tpb_dir_menu (A's menu parent) and tpa:view (B's role permission) exist now too → the
        // cyclic menu-parent and role-permission refs resolve as well. (Residue-tolerant: asserts
        // only that the cyclic references are not flagged, not that the list is empty.)
        List<String> dangling = pluginImportService.verifyImportReferenceIntegrity();
        assertThat(dangling)
                .as("cyclic refs must resolve once both sides are imported, but got: %s", dangling)
                .noneMatch(s -> s.contains("tpa:use_b"))
                .noneMatch(s -> s.contains("tpb:use_a"))
                .noneMatch(s -> s.contains("tpa_child_menu"))
                .noneMatch(s -> s.contains("tpb_role"));
    }

    /**
     * Closing sweep flags every kind of dangling cross-plugin reference deferral lets through:
     * command->model, menu->parent-menu and role->permission, all owned by nobody. Single import
     * of plugin-c (not repeated across test methods) — re-importing the same plugin twice in one
     * tenant hits an unrelated pre-existing "model exists but cannot be loaded for update" bug in
     * the OVERWRITE update path, so every dangling-reference kind is asserted off this one import.
     */
    @Test
    @DisplayName("closing sweep flags command->model, menu->parent-menu and role->permission references no plugin provides")
    void deferral_danglingReference_flaggedBySweep() throws Exception {
        // Deferral lets the dangling plugin import even though tp_nonexistent_model / _menu /
        // _permission exist nowhere.
        ImportExecuteResult c = importDir(PLUGIN_C_DANGLING, true);
        assertThat(c.isSuccess()).as("plugin-c (deferred) import failed: %s", c.getErrorMessage()).isTrue();

        // The closing sweep re-enforces integrity: the never-provided references must surface.
        List<String> dangling = pluginImportService.verifyImportReferenceIntegrity();
        assertThat(dangling)
                .as("sweep must flag tpc:dangling -> tp_nonexistent_model, got: %s", dangling)
                .anyMatch(s -> s.contains("tpc:dangling") && s.contains("tp_nonexistent_model"));
        assertThat(dangling)
                .as("sweep must flag tpc_dangling_menu -> tp_nonexistent_menu, got: %s", dangling)
                .anyMatch(s -> s.contains("tpc_dangling_menu") && s.contains("tp_nonexistent_menu"));
        assertThat(dangling)
                .as("sweep must flag tpc_dangling_role -> tp_nonexistent_permission, got: %s", dangling)
                .anyMatch(s -> s.contains("tpc_dangling_role") && s.contains("tp_nonexistent_permission"));
    }
}
