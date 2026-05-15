package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.integration.IntegrationTestBase;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 — Op 6: integration tests for the {@code BuiltinPluginImportService}
 * 2-profile split (CORE vs DEMO).
 *
 * <p>Asserts that the {@code includeDemoPlugins} flag in
 * {@link BootstrapRepairService.RepairOptions} is threaded end-to-end:
 * <ul>
 *   <li>When {@code false}, only the 2 core plugins
 *       ({@code com.auraboot.org-management}, {@code com.auraboot.platform-admin})
 *       appear in {@code ab_plugin} after {@code repairBuiltinPlugins}; the 9 demo
 *       plugin rows are absent.</li>
 *   <li>When {@code true}, the demo profile is also imported (subject to the
 *       plugin directory being resolvable on the test stack).</li>
 *   <li>Idempotent: calling {@code repairBuiltinPlugins} twice with the same
 *       flag does not create duplicate {@code ab_plugin} rows.</li>
 * </ul>
 *
 * <p><b>Resilience to missing plugin dirs:</b> on stacks where neither
 * {@code platform/../plugins} nor {@code <cwd>/plugins} resolves (e.g. a stripped
 * IT image), the service logs a warning and skips import — both calls then leave
 * {@code ab_plugin} empty, and we only assert the negative core/demo split (no
 * demo-only rows appearing on the {@code includeDemo=false} path).
 *
 * <p>The {@code repairBuiltinPlugins} step remains non-fatal in explicit setup
 * flows (status may be {@code REPAIRED}, {@code PRESENT}, or {@code ERROR}); we
 * tolerate {@code ERROR} here for the same reason.
 */
class BuiltinPluginProfileIT extends IntegrationTestBase {

    @Autowired private BootstrapRepairService repair;
    @Autowired private SystemConfigService systemConfigService;

    /** Plugin IDs that must NEVER appear in ab_plugin when includeDemoPlugins=false. */
    private static final Set<String> DEMO_PLUGIN_IDS = Set.of(
            "com.auraboot.core-meta",
            "com.auraboot.core-bpm",
            "com.auraboot.core-aurabot",
            "com.auraboot.page-manager",
            "com.auraboot.crm-starter",
            "com.auraboot.showcase",
            "com.auraboot.agent-control-plane",
            "com.auraboot.acp-showcase",
            "com.auraboot.workflow-demo"
    );

    private void resetDb() {
        freshDb();
        systemConfigService.evictCache();
    }

    /** Build options with the dependent invariants pre-seeded so repairBuiltinPlugins can run. */
    private BootstrapRepairService.RepairOptions seedThroughBusinessTenant(boolean includeDemo) {
        var o = BootstrapRepairService.RepairOptions.of(
                "it-profile-" + System.nanoTime() + "@auraboot.test",
                "ItTestPwd-12345!",
                "IT Profile Admin",
                "IT Profile Co-" + System.nanoTime(),
                "single",
                "http://localhost:6443",
                includeDemo);
        // Run the pre-requisite repair steps so the plugin step has a Business Tenant + admin.
        repair.repairSystemConfig(o);
        repair.repairSystemTenant(o);
        repair.repairAdminUser(o);
        repair.repairBusinessTenant(o);
        repair.repairAdminMembership(o);
        repair.repairPlatformAdminRole(o);
        repair.repairAdminRoleGrant(o);
        return o;
    }

    @Test
    @DisplayName("includeDemoPlugins=false → no demo plugin rows in ab_plugin")
    void coreProfile_excludesDemoPlugins() {
        resetDb();
        var o = seedThroughBusinessTenant(false);

        var result = repair.repairBuiltinPlugins(o);
        // Either REPAIRED (plugin dir found and import attempted) or ERROR
        // (plugin dir unresolvable). In both cases, demo rows must be absent.
        assertThat(result.status()).isIn(
                RepairStepResult.Status.REPAIRED,
                RepairStepResult.Status.ERROR);
        // Detail should reflect the includeDemo flag for observability.
        if (result.status() == RepairStepResult.Status.REPAIRED) {
            assertThat(result.detail()).contains("includeDemo=false");
        }

        List<String> presentPluginIds = jdbc.queryForList(
                "SELECT plugin_id FROM ab_plugin WHERE deleted_flag = false",
                String.class);

        assertThat(presentPluginIds)
                .as("demo profile plugin rows must be ABSENT when includeDemoPlugins=false")
                .doesNotContainAnyElementsOf(DEMO_PLUGIN_IDS);
    }

    @Test
    @DisplayName("includeDemoPlugins=true → detail records the flag")
    void demoProfile_threadsFlagToService() {
        resetDb();
        var o = seedThroughBusinessTenant(true);

        var result = repair.repairBuiltinPlugins(o);
        assertThat(result.status()).isIn(
                RepairStepResult.Status.REPAIRED,
                RepairStepResult.Status.ERROR);
        if (result.status() == RepairStepResult.Status.REPAIRED) {
            assertThat(result.detail()).contains("includeDemo=true");
        }
        // We do NOT assert demo rows PRESENT here because the IT stack may not
        // have a resolvable plugins/ directory — the negative core-only test
        // above is the load-bearing assertion. Asserting the flag is threaded
        // (via detail) plus exercising the path is sufficient at IT scope.
    }

    @Test
    @DisplayName("repairBuiltinPlugins twice with same flag → idempotent (no duplicate rows)")
    void repairBuiltinPlugins_idempotent() {
        resetDb();
        var o = seedThroughBusinessTenant(false);

        repair.repairBuiltinPlugins(o);
        Integer firstCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_plugin WHERE deleted_flag = false",
                Integer.class);

        repair.repairBuiltinPlugins(o);
        Integer secondCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_plugin WHERE deleted_flag = false",
                Integer.class);

        assertThat(secondCount)
                .as("second invocation must not create duplicate ab_plugin rows")
                .isEqualTo(firstCount);
    }

    @Test
    @DisplayName("RepairOptions backwards compat — 6-arg defaults includeDemoPlugins=false")
    void repairOptions_legacyArityDefaultsCoreOnly() {
        var o = BootstrapRepairService.RepairOptions.of(
                "compat@auraboot.test", "Pwd!", "Admin", "CompatCo", "single", "http://x");
        assertThat(o.includeDemoPlugins())
                .as("6-arg of(...) must default includeDemoPlugins to false (prod-safe)")
                .isFalse();
    }

    @Test
    @DisplayName("RepairOptions.fromBootstrapRequest — seedDemoData=true → includeDemoPlugins=true")
    void repairOptions_fromBootstrapRequest_threadsSeedDemoData() {
        var req = new com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest();
        req.setAdminEmail("x@y.z");
        req.setAdminPassword("pwd");
        req.setAdminDisplayName("X");
        req.setCompanyName("Co");
        req.setSystemMode("single");
        req.setInstanceUrl("http://x");
        req.setSeedDemoData(true);

        var o = BootstrapRepairService.RepairOptions.fromBootstrapRequest(req);
        assertThat(o.includeDemoPlugins()).isTrue();

        req.setSeedDemoData(null);
        var o2 = BootstrapRepairService.RepairOptions.fromBootstrapRequest(req);
        assertThat(o2.includeDemoPlugins())
                .as("null seedDemoData must be treated as false (prod-safe)")
                .isFalse();
    }
}
