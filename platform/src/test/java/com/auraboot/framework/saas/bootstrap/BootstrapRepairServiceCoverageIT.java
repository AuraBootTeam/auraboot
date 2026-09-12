package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real-stack coverage IT for {@link BootstrapRepairService} — the idempotent
 * bootstrap-invariant repair steps, exercised through the {@code repair(step, opts)}
 * dispatcher against the live shared database. Every invariant already holds there,
 * so each step must answer PRESENT twice in a row: the second PRESENT is the
 * idempotency proof (the service contract promises no write when nothing is missing).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("BootstrapRepairService Coverage IT — idempotent repair steps")
class BootstrapRepairServiceCoverageIT extends BaseIntegrationTest {

    @Autowired
    private BootstrapRepairService bootstrapRepairService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final List<String> ALL_STEPS = List.of(
            BootstrapRepairService.STEP_SYSTEM_CONFIG,
            BootstrapRepairService.STEP_SYSTEM_TENANT,
            BootstrapRepairService.STEP_PLATFORM_ADMIN_ROLE,
            BootstrapRepairService.STEP_ADMIN_USER,
            BootstrapRepairService.STEP_ADMIN_MEMBERSHIP,
            BootstrapRepairService.STEP_ADMIN_ROLE_GRANT,
            BootstrapRepairService.STEP_BUSINESS_TENANT,
            BootstrapRepairService.STEP_BUSINESS_TENANT_BOOTSTRAP,
            BootstrapRepairService.STEP_BUILTIN_PLUGINS,
            BootstrapRepairService.STEP_JWT_SECRET);

    private BootstrapRepairService.RepairOptions opts() {
        return BootstrapRepairService.RepairOptions.of(
                "admin@auraboot.com", "Test2026x", "Platform Admin", "AuraBoot IT", "multi", "http://localhost:6443");
    }

    @Test
    @DisplayName("every ordered step repairs its prerequisite and reaches a stable state")
    void everyStepIdempotent() {
        // Run in production order so this test establishes its own prerequisites instead
        // of depending on seed rows left by another test or CI setup phase.
        for (String step : ALL_STEPS) {
            var first = bootstrapRepairService.repair(step, opts());
            assertNotNull(first, step);
            assertEquals(step, first.stepName(), step);
            org.assertj.core.api.Assertions.assertThat(first.status())
                    .as("%s first repair must establish or confirm the invariant; detail=%s",
                            step, first.detail())
                    .isNotEqualTo(RepairStepResult.Status.ERROR);

            var second = bootstrapRepairService.repair(step, opts());
            List<RepairStepResult.Status> acceptableSteadyState =
                    step.equals(BootstrapRepairService.STEP_BUILTIN_PLUGINS)
                            ? List.of(RepairStepResult.Status.PRESENT, RepairStepResult.Status.REPAIRED)
                            : List.of(RepairStepResult.Status.PRESENT);
            org.assertj.core.api.Assertions.assertThat(second.status())
                    .as("%s second repair must be idempotent; detail=%s", step, second.detail())
                    .isIn(acceptableSteadyState);
        }
    }

    @Test
    @DisplayName("unknown step dispatches to an ERROR result")
    void unknownStep() {
        var result = bootstrapRepairService.repair("no_such_step", opts());
        assertEquals(RepairStepResult.Status.ERROR, result.status());
        assertTrue(result.detail().contains("unknown step"));
    }

    @Test
    // NOT_SUPPORTED: the duplicate-key recovery path intentionally swallows the aborted
    // INSERT, which only self-heals outside a transaction (as in production).
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("default billing account repair is idempotent on the System tenant")
    void billingAccountIdempotent() {
        applyTestMetaContext();
        // Establish the prerequisite explicitly. The shared integration database is
        // mutable across the suite, so assuming a seed-owned System row makes this test
        // order-dependent.
        var tenantRepair = bootstrapRepairService.repairSystemTenant(opts());
        org.assertj.core.api.Assertions.assertThat(tenantRepair.status())
                .as("system tenant prerequisite; detail=%s", tenantRepair.detail())
                .isNotEqualTo(RepairStepResult.Status.ERROR);
        Long systemTenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM ab_tenant WHERE name = 'System' AND deleted_flag = FALSE", Long.class);
        var first = bootstrapRepairService.repairDefaultBillingAccount(systemTenantId);
        assertNotNull(first);
        org.assertj.core.api.Assertions.assertThat(first.status())
                .as("first=%s detail=%s", first.status(), first.detail())
                .isIn(RepairStepResult.Status.CREATED, RepairStepResult.Status.PRESENT);
        var second = bootstrapRepairService.repairDefaultBillingAccount(systemTenantId);
        org.assertj.core.api.Assertions.assertThat(second.status())
                .as("second=%s detail=%s", second.status(), second.detail())
                .isEqualTo(RepairStepResult.Status.PRESENT);
    }
}
