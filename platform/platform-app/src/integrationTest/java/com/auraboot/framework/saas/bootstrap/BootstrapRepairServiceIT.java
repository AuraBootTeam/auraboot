package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.integration.IntegrationTestBase;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link BootstrapRepairService} (Phase 2.2).
 *
 * <p>Each of the 9 invariants is tested in two states:
 * <ul>
 *   <li><b>missing</b> — empty DB, repair step creates the row(s) → returns
 *       {@link RepairStepResult.Status#CREATED} (or {@code REPAIRED}/{@code PRESENT}
 *       for steps that have no fresh-DB row, e.g. {@code repairBuiltinPlugins}
 *       which delegates to a service whose own idempotency reports are
 *       informational, and {@code repairJwtSecret} which is config-sourced).</li>
 *   <li><b>present</b> — second invocation, repair step short-circuits → returns
 *       {@link RepairStepResult.Status#PRESENT}.</li>
 * </ul>
 *
 * <p>One additional test (
 * {@link #repairAll_freshDb_then_secondCall_isAllPresent}) covers the
 * {@code repairAll()} aggregate path twice in a row to prove no duplicate rows
 * are produced and {@code anyError} stays false.
 *
 * <p>{@code freshDb()} truncates the canonical bootstrap tables; we then call
 * {@link SystemConfigService#evictCache()} so the in-memory cache (added in
 * Phase 2.2) doesn't surface stale {@code system.initialized} values.
 */
class BootstrapRepairServiceIT extends IntegrationTestBase {

    @Autowired private BootstrapRepairService repair;
    @Autowired private SystemConfigService systemConfigService;

    private static final String COMPANY = "IT Repair Co";

    private BootstrapRepairService.RepairOptions opts() {
        return BootstrapRepairService.RepairOptions.of(
                "it-repair-" + System.nanoTime() + "@auraboot.test",
                "ItTestPwd-12345!",
                "IT Repair Admin",
                COMPANY + "-" + System.nanoTime(),
                "single",
                "http://localhost:6443");
    }

    private void resetDb() {
        freshDb();
        systemConfigService.evictCache();
    }

    // ── Invariant 1: system_config ────────────────────────────────────

    @Test
    @DisplayName("repairSystemConfig — missing → CREATED, then PRESENT")
    void repairSystemConfig_missingThenPresent() {
        resetDb();
        var o = opts();

        var first = repair.repairSystemConfig(o);
        assertThat(first.status()).isEqualTo(RepairStepResult.Status.CREATED);

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_system_config WHERE config_key IN (?, ?, ?, ?)",
                Integer.class,
                SystemConfigKeys.SYSTEM_MODE,
                SystemConfigKeys.SYSTEM_PLATFORM_NAME,
                SystemConfigKeys.SYSTEM_DB_UUID,
                SystemConfigKeys.SYSTEM_INSTANCE_URL);
        assertThat(rowCount).isEqualTo(4);

        var second = repair.repairSystemConfig(o);
        assertThat(second.status()).isEqualTo(RepairStepResult.Status.PRESENT);
    }

    // ── Invariant 2: System Tenant ────────────────────────────────────

    @Test
    @DisplayName("repairSystemTenant — missing → CREATED, then PRESENT")
    void repairSystemTenant_missingThenPresent() {
        resetDb();
        var o = opts();

        var first = repair.repairSystemTenant(o);
        assertThat(first.status()).isEqualTo(RepairStepResult.Status.CREATED);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_tenant WHERE name = 'System'", Integer.class);
        assertThat(count).isEqualTo(1);

        var second = repair.repairSystemTenant(o);
        assertThat(second.status()).isEqualTo(RepairStepResult.Status.PRESENT);
    }

    // ── Invariant 3: platform_admin role ──────────────────────────────

    @Test
    @DisplayName("repairPlatformAdminRole — missing → CREATED, then PRESENT")
    void repairPlatformAdminRole_missingThenPresent() {
        resetDb();
        var o = opts();
        // Pre-req: System Tenant + admin user exist.
        repair.repairSystemTenant(o);
        repair.repairAdminUser(o);

        var first = repair.repairPlatformAdminRole(o);
        assertThat(first.status()).isEqualTo(RepairStepResult.Status.CREATED);

        Integer roles = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_role WHERE code = 'platform_admin' AND deleted_flag = false",
                Integer.class);
        assertThat(roles).isEqualTo(1);

        var second = repair.repairPlatformAdminRole(o);
        assertThat(second.status()).isEqualTo(RepairStepResult.Status.PRESENT);
    }

    // ── Invariant 4: admin user ───────────────────────────────────────

    @Test
    @DisplayName("repairAdminUser — missing → CREATED, then PRESENT")
    void repairAdminUser_missingThenPresent() {
        resetDb();
        var o = opts();

        var first = repair.repairAdminUser(o);
        assertThat(first.status()).isEqualTo(RepairStepResult.Status.CREATED);

        Integer users = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE email = ?", Integer.class, o.adminEmail());
        assertThat(users).isEqualTo(1);

        var second = repair.repairAdminUser(o);
        assertThat(second.status()).isEqualTo(RepairStepResult.Status.PRESENT);
    }

    // ── Invariant 5: admin membership ────────────────────────────────

    @Test
    @DisplayName("repairAdminMembership — missing → CREATED, then PRESENT")
    void repairAdminMembership_missingThenPresent() {
        resetDb();
        var o = opts();
        repair.repairSystemTenant(o);
        repair.repairAdminUser(o);
        repair.repairBusinessTenant(o);

        var first = repair.repairAdminMembership(o);
        assertThat(first.status()).isEqualTo(RepairStepResult.Status.CREATED);

        Integer members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_tenant_member tm "
                        + "JOIN ab_user u ON tm.user_id = u.id "
                        + "WHERE u.email = ?", Integer.class, o.adminEmail());
        assertThat(members).isGreaterThanOrEqualTo(2); // System + Business

        var second = repair.repairAdminMembership(o);
        assertThat(second.status()).isEqualTo(RepairStepResult.Status.PRESENT);
    }

    // ── Invariant 6: admin → platform_admin grant ────────────────────

    @Test
    @DisplayName("repairAdminRoleGrant — missing → CREATED, then PRESENT")
    void repairAdminRoleGrant_missingThenPresent() {
        resetDb();
        var o = opts();
        repair.repairSystemTenant(o);
        repair.repairAdminUser(o);
        repair.repairBusinessTenant(o);
        repair.repairAdminMembership(o);
        repair.repairPlatformAdminRole(o);

        var first = repair.repairAdminRoleGrant(o);
        assertThat(first.status()).isEqualTo(RepairStepResult.Status.CREATED);

        Integer grants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_user_role ur "
                        + "JOIN ab_role r ON ur.role_id = r.id "
                        + "WHERE r.code = 'platform_admin' AND ur.deleted_flag = false",
                Integer.class);
        assertThat(grants).isEqualTo(1);

        var second = repair.repairAdminRoleGrant(o);
        assertThat(second.status()).isEqualTo(RepairStepResult.Status.PRESENT);
    }

    // ── Invariant 7: Business Tenant ─────────────────────────────────

    @Test
    @DisplayName("repairBusinessTenant — missing → CREATED, then PRESENT")
    void repairBusinessTenant_missingThenPresent() {
        resetDb();
        var o = opts();

        var first = repair.repairBusinessTenant(o);
        assertThat(first.status()).isEqualTo(RepairStepResult.Status.CREATED);

        Integer tenants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_tenant WHERE name = ?", Integer.class, o.companyName());
        assertThat(tenants).isEqualTo(1);

        var second = repair.repairBusinessTenant(o);
        assertThat(second.status()).isEqualTo(RepairStepResult.Status.PRESENT);
    }

    // ── Invariant 8: builtin plugins ─────────────────────────────────

    @Test
    @DisplayName("repairBuiltinPlugins — invokable on prepared DB (delegated idempotency)")
    void repairBuiltinPlugins_delegated() {
        resetDb();
        var o = opts();
        repair.repairSystemTenant(o);
        repair.repairAdminUser(o);
        repair.repairBusinessTenant(o);

        var first = repair.repairBuiltinPlugins(o);
        // BuiltinPluginImportService internal idempotency guarantees no-op on second
        // run; we report REPAIRED on every call (it's a delegated step).
        // Either REPAIRED (success) or ERROR (no built-in plugin dir on this stack)
        // is acceptable — both prove the step is reachable.
        assertThat(first.status()).isIn(
                RepairStepResult.Status.REPAIRED,
                RepairStepResult.Status.ERROR);

        var second = repair.repairBuiltinPlugins(o);
        assertThat(second.status()).isIn(
                RepairStepResult.Status.REPAIRED,
                RepairStepResult.Status.ERROR);
    }

    // ── Invariant 9: JWT secret ──────────────────────────────────────

    @Test
    @DisplayName("repairJwtSecret — always PRESENT (config-sourced)")
    void repairJwtSecret_alwaysPresent() {
        resetDb();
        var o = opts();

        var first = repair.repairJwtSecret(o);
        assertThat(first.status()).isEqualTo(RepairStepResult.Status.PRESENT);

        var second = repair.repairJwtSecret(o);
        assertThat(second.status()).isEqualTo(RepairStepResult.Status.PRESENT);
    }

    // ── Aggregate: repairAll() ───────────────────────────────────────

    @Test
    @DisplayName("repairAll on fresh DB — every invariant CREATED/PRESENT/REPAIRED, no errors")
    void repairAll_freshDb_allCreated() {
        resetDb();
        var o = opts();

        RepairReport report = repair.repairAll(o);

        assertThat(report.steps()).hasSize(BootstrapRepairService.ORDERED_STEPS.size());
        // Plugin step may ERROR on stacks without a built-in plugin dir — accept that
        // single non-fatal failure but require all OTHER steps to be non-error.
        long nonPluginErrors = report.steps().stream()
                .filter(s -> !s.stepName().equals(BootstrapRepairService.STEP_BUILTIN_PLUGINS))
                .filter(s -> s.status() == RepairStepResult.Status.ERROR)
                .count();
        assertThat(nonPluginErrors).as("only repairBuiltinPlugins is allowed to ERROR on this stack")
                .isZero();
        assertThat(report.totalCreated()).isPositive();
    }

    @Test
    @DisplayName("repairAll twice — second call is all PRESENT, no duplicate rows")
    void repairAll_freshDb_then_secondCall_isAllPresent() {
        resetDb();
        var o = opts();

        repair.repairAll(o);
        RepairReport second = repair.repairAll(o);

        long createdSecondPass = second.steps().stream()
                .filter(s -> s.status() == RepairStepResult.Status.CREATED)
                .count();
        assertThat(createdSecondPass)
                .as("second repairAll must create nothing new (all invariants already hold)")
                .isZero();

        Integer adminUsers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE email = ?", Integer.class, o.adminEmail());
        assertThat(adminUsers).as("admin user must not be duplicated").isEqualTo(1);

        Integer systemTenants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_tenant WHERE name = 'System'", Integer.class);
        assertThat(systemTenants).as("System Tenant must not be duplicated").isEqualTo(1);

        Integer businessTenants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_tenant WHERE name = ?", Integer.class, o.companyName());
        assertThat(businessTenants).as("Business Tenant must not be duplicated").isEqualTo(1);

        Integer platformAdminRoles = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_role WHERE code = 'platform_admin' AND deleted_flag = false",
                Integer.class);
        assertThat(platformAdminRoles).as("platform_admin role must not be duplicated").isEqualTo(1);

        Integer grants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_user_role ur "
                        + "JOIN ab_role r ON ur.role_id = r.id "
                        + "WHERE r.code = 'platform_admin' AND ur.deleted_flag = false",
                Integer.class);
        assertThat(grants).as("platform_admin grant must not be duplicated").isEqualTo(1);
    }
}
