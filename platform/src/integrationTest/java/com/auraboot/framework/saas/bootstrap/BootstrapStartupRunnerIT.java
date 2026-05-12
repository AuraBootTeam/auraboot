package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.integration.IntegrationTestBase;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link BootstrapStartupRunner} (Phase 2.3 of the
 * bootstrap-unified plan).
 *
 * <p>Coverage:
 * <ul>
 *   <li>Happy path on fresh DB — every invariant gets CREATED, runner exits 0
 *       and the bean is wired by Spring</li>
 *   <li>Restart on pre-seeded DB — every invariant returns PRESENT, no
 *       duplicate rows, runner exits 0</li>
 *   <li>Disabled flag — runner bean is NOT registered when
 *       {@code auraboot.bootstrap.enabled=false}</li>
 *   <li>Error escalation — when one repair step returns ERROR (other than
 *       the documented non-fatal {@code builtin_plugins} step) the runner
 *       throws {@link IllegalStateException} to fail startup</li>
 * </ul>
 *
 * <p>The runner bean is gated by {@code @ConditionalOnProperty}; the parent
 * {@link IntegrationTestBase} runs without the property set (so the default
 * {@code application.yml} resolution lands on {@code false}). Tests that need
 * the runner active set the property explicitly via {@link TestPropertySource}.
 */
@TestPropertySource(properties = "auraboot.bootstrap.enabled=true")
class BootstrapStartupRunnerIT extends IntegrationTestBase {

    @Autowired private BootstrapStartupRunner runner;
    @Autowired private BootstrapRepairService repair;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private ApplicationContext ctx;

    @Test
    @DisplayName("runner bean is registered when auraboot.bootstrap.enabled=true")
    void runnerBean_isRegistered() {
        assertThat(runner).isNotNull();
        assertThat(ctx.getBeansOfType(BootstrapStartupRunner.class)).hasSize(1);
    }

    @Test
    @DisplayName("happy path on fresh DB → all 9 steps reachable, no fatal errors")
    void runner_onFreshDb_runsAllSteps() throws Exception {
        freshDb();
        systemConfigService.evictCache();

        // The runner.run(...) loads its own RepairOptions from
        // bootstrap-seed-config.json — proves the seed-config JSON loader survived
        // the merge from BootstrapStartupListener.
        runner.run(null);

        // Verify the 9 bootstrap invariants now hold by re-running repairAll() with
        // the seed-config defaults — every step must be PRESENT (no creates).
        var opts = BootstrapRepairService.RepairOptions.of(
                "admin@auraboot.com", "Test2026x", "Admin",
                "AuraBoot Dev", "single", "http://localhost:6443");
        RepairReport second = repair.repairAll(opts);

        long createdSecondPass = second.steps().stream()
                .filter(s -> s.status() == RepairStepResult.Status.CREATED)
                .count();
        assertThat(createdSecondPass)
                .as("after the runner ran, no invariant should still be missing")
                .isZero();

        // No duplicate admin / tenants.
        Integer adminUsers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE email = ?",
                Integer.class, "admin@auraboot.com");
        assertThat(adminUsers).isEqualTo(1);

        Integer systemTenants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_tenant WHERE name = 'System'", Integer.class);
        assertThat(systemTenants).isEqualTo(1);
    }

    @Test
    @DisplayName("restart on pre-seeded DB → runner exits cleanly, no duplicate rows")
    void runner_onPreSeededDb_isIdempotent() throws Exception {
        freshDb();
        systemConfigService.evictCache();

        // Seed the DB once via the runner.
        runner.run(null);
        Integer adminCountAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE email = ?",
                Integer.class, "admin@auraboot.com");
        assertThat(adminCountAfterFirst).isEqualTo(1);

        // Second invocation must not duplicate anything and must not throw.
        runner.run(null);

        Integer adminCountAfterSecond = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE email = ?",
                Integer.class, "admin@auraboot.com");
        assertThat(adminCountAfterSecond)
                .as("admin user must not be duplicated across restarts")
                .isEqualTo(1);

        Integer platformAdminRoles = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_role WHERE code = 'platform_admin' AND deleted_flag = false",
                Integer.class);
        assertThat(platformAdminRoles)
                .as("platform_admin role must not be duplicated")
                .isEqualTo(1);
    }

    /**
     * Disabled-flag test — uses a separate static-nested class so the
     * {@code matchIfMissing=false} conditional resolves to "missing bean" with
     * the property explicitly set to {@code false}. Default in the parent
     * {@link IntegrationTestBase} class also resolves to false (env-default),
     * but exercising the explicit-false path catches misconfigured profile
     * overrides too.
     */
    @org.springframework.test.context.TestPropertySource(
            properties = "auraboot.bootstrap.enabled=false")
    static class DisabledFlag extends IntegrationTestBase {

        @Autowired private ApplicationContext ctx;

        @Test
        @DisplayName("runner bean is NOT registered when auraboot.bootstrap.enabled=false")
        void runnerBean_isAbsent() {
            Map<String, BootstrapStartupRunner> beans =
                    ctx.getBeansOfType(BootstrapStartupRunner.class);
            assertThat(beans).isEmpty();
        }
    }

    @Test
    @DisplayName("repair step ERROR (non-builtin_plugins) → runner throws IllegalStateException")
    void runner_onFatalRepairError_throws() {
        // Use a tiny stand-in runner with a stubbed repair service so we don't
        // have to corrupt the live DB to provoke an error. This proves the
        // ERROR-routing logic in BootstrapStartupRunner.run().
        BootstrapRepairService stub = new BootstrapRepairService(
                null, null, null, null, null, null, null, null, null, null) {
            @Override
            public RepairReport repairAll(BootstrapRepairService.RepairOptions opts) {
                return RepairReport.from(List.of(
                        RepairStepResult.created(STEP_SYSTEM_CONFIG, "ok"),
                        RepairStepResult.error(STEP_ADMIN_USER, "simulated failure")));
            }
        };
        BootstrapStartupRunner stubbedRunner = new BootstrapStartupRunner(
                stub, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> stubbedRunner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invariants failed to repair");
    }

    @Test
    @DisplayName("non-fatal builtin_plugins ERROR does NOT fail startup")
    void runner_onPluginOnlyError_continues() throws Exception {
        BootstrapRepairService stub = new BootstrapRepairService(
                null, null, null, null, null, null, null, null, null, null) {
            @Override
            public RepairReport repairAll(BootstrapRepairService.RepairOptions opts) {
                return RepairReport.from(List.of(
                        RepairStepResult.created(STEP_SYSTEM_CONFIG, "ok"),
                        RepairStepResult.error(STEP_BUILTIN_PLUGINS, "no plugin dir")));
            }
        };
        BootstrapStartupRunner stubbedRunner = new BootstrapStartupRunner(
                stub, new com.fasterxml.jackson.databind.ObjectMapper());

        // Should not throw — builtin_plugins is documented as non-fatal.
        stubbedRunner.run(null);
    }
}
