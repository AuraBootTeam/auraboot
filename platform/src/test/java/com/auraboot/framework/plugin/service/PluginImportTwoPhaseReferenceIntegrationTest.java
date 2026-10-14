package com.auraboot.framework.plugin.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.exception.PluginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the two-phase cross-plugin reference fix against a real PostgreSQL DB.
 *
 * <p>Reproduces the crm↔sales cold-reset cycle with two fixture plugins whose commands reference
 * each other's model, plus dangling fixtures. Proves end-to-end (parse → execute → sweep):
 * <ol>
 *   <li>The default (strict) mode still hard-errors on a cross-plugin command→model reference —
 *       the regression guard proving the fix did not silently weaken validation.</li>
 *   <li>With {@code deferReferenceValidation}, a cyclic plugin pair imports successfully even though
 *       no per-plugin order satisfies the strict rule, and the closing sweep then finds the cyclic
 *       references intact. Command→model is the deferral leg: it is not governed by the
 *       role-permission fail-closed reconcile.</li>
 *   <li>A command referencing a model no plugin provides still imports under deferral but is flagged
 *       by {@link PluginImportService#verifyImportReferenceIntegrity()}.</li>
 *   <li>Role→permission and menu→parent-menu references no plugin provides fail the IMPORT closed
 *       with locatable errors (role-permission fail-closed: the historic warn+skip silently
 *       degraded roles and silently re-parented menus; both surfaced rounds later as findings).
 *       The closing sweep therefore only ever sees the command→model leg.</li>
 * </ol>
 *
 * Uses real PostgreSQL (no H2/mock); extends {@link BaseIntegrationTest} for MetaContext + tenant.
 */
class PluginImportTwoPhaseReferenceIntegrationTest extends BaseIntegrationTest {

    private static final String PLUGIN_A = "plugin-test/twophase-cycle/plugin-a";
    private static final String PLUGIN_B = "plugin-test/twophase-cycle/plugin-b";
    private static final String PLUGIN_C_DANGLING = "plugin-test/twophase-cycle/plugin-c-dangling";
    private static final String PLUGIN_D_DANGLING_COMMAND = "plugin-test/twophase-cycle/plugin-d-dangling-command";
    private static final String PLUGIN_E_DANGLING_ROLE = "plugin-test/twophase-cycle/plugin-e-dangling-role";

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
        // plugin-a's command references tp_b_model (owned by B, not yet imported): allowed by
        // deferral. Its menu is now self-rooted — a not-yet-imported parent menu would fail
        // closed under the role/menu fail-closed contract instead of silently re-parenting.
        ImportExecuteResult a = importDir(PLUGIN_A, true);
        assertThat(a.isSuccess()).as("plugin-a import failed: %s", a.getErrorMessage()).isTrue();

        // plugin-b's command references tp_a_model, now provided by A; plugin-b's role references
        // A's permission tpa:view, now provided by A (provider imported first — resolves in order).
        ImportExecuteResult b = importDir(PLUGIN_B, true);
        assertThat(b.isSuccess()).as("plugin-b import failed: %s", b.getErrorMessage()).isTrue();

        // Both tp_a_model and tp_b_model exist now → the cyclic command/model refs resolve.
        // tpa:view (B's role permission) exists too → resolves as well. (Residue-tolerant:
        // asserts only that the cyclic references are not flagged, not that the list is empty.)
        List<String> dangling = pluginImportService.verifyImportReferenceIntegrity();
        assertThat(dangling)
                .as("cyclic refs must resolve once both sides are imported, but got: %s", dangling)
                .noneMatch(s -> s.contains("tpa:use_b"))
                .noneMatch(s -> s.contains("tpb:use_a"))
                .noneMatch(s -> s.contains("tpb_role"));
    }

    /**
     * Command→model is the deferral leg: a command referencing a model no plugin provides still
     * imports under deferral and is flagged by the closing sweep.
     */
    @Test
    @DisplayName("closing sweep flags command->model references no plugin provides")
    void deferral_danglingCommandModel_importsAndIsFlaggedBySweep() throws Exception {
        ImportExecuteResult d = importDir(PLUGIN_D_DANGLING_COMMAND, true);
        assertThat(d.isSuccess()).as("plugin-d (deferred) import failed: %s", d.getErrorMessage()).isTrue();

        List<String> dangling = pluginImportService.verifyImportReferenceIntegrity();
        assertThat(dangling)
                .as("sweep must flag tpd:dangling -> tp_nonexistent_model, got: %s", dangling)
                .anyMatch(s -> s.contains("tpd:dangling") && s.contains("tp_nonexistent_model"));
    }

    /**
     * Role-permission fail-closed contract: a role declaring a permission no plugin provides
     * fails the import (rolled back) with a locatable error instead of the historic warn+skip
     * that silently degraded the role.
     */
    @Test
    @DisplayName("dangling role->permission reference fails the import closed with a locatable error")
    void deferral_danglingRolePermission_failsClosedWithLocatableError() throws Exception {
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(PLUGIN_E_DANGLING_ROLE), true);
        assertThat(preview.isValid())
                .as("deferral still skips the preview-level check, but had errors: %s", preview.getErrors())
                .isTrue();

        assertThatThrownBy(() -> pluginImportService.execute(preview.getImportId(),
                ImportRequest.builder()
                        .importId(preview.getImportId())
                        .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                        .build()))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("tpe_dangling_role")
                .hasMessageContaining("tp_nonexistent_permission")
                .hasMessageContaining("provider plugin");
    }

    /**
     * Menu fail-closed contract: a menu whose parentCode no plugin provides fails the import
     * instead of being silently re-parented to root (the old behavior permanently corrupted
     * navigation; the closing sweep only flagged it after the fact).
     */
    @Test
    @DisplayName("dangling menu->parent-menu reference fails the import closed with a locatable error")
    void deferral_danglingMenuParent_failsClosedWithLocatableError() throws Exception {
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(PLUGIN_C_DANGLING), true);
        assertThat(preview.isValid())
                .as("deferral still skips the preview-level check, but had errors: %s", preview.getErrors())
                .isTrue();

        // The menu import stage runs before the post-publication reconcile, so the menu
        // parent failure is the one that surfaces for this fixture (its role declaration
        // is covered by plugin-e).
        assertThatThrownBy(() -> pluginImportService.execute(preview.getImportId(),
                ImportRequest.builder()
                        .importId(preview.getImportId())
                        .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                        .build()))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("tpc_dangling_menu")
                .hasMessageContaining("tp_nonexistent_menu");
    }
}
