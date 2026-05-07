package com.auraboot.framework.application.security;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AdminRoleChecker#hasRole(Long, Long, String)}.
 *
 * <p>Uses a real Postgres DB (via {@link BaseIntegrationTest}) — no mocks for
 * DB/Redis per project convention. Each test seeds its own isolated tenant row
 * via direct JDBC so the shared {@code testUser} is reused without polluting
 * the base test data.
 *
 * <p>Cleanup is performed in {@code @AfterEach} against the seeded tenant id so
 * that failed tests do not leave dangling rows.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("AdminRoleChecker.hasRole() — parametrized role lookup")
class AdminRoleCheckerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AdminRoleChecker adminRoleChecker;

    // SpyBean so cache tests can clear invocations and verify JDBC call count.
    // Delegates to the real bean for all seeding writes in existing tests.
    @SpyBean
    private JdbcTemplate jdbc;

    private Long tenantId;

    @AfterEach
    void cleanup() {
        // Invalidate cache so seeded keys from one test never bleed into the next.
        adminRoleChecker.invalidateAll();
        if (tenantId != null) {
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant_member WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant WHERE id = ?", tenantId);
            tenantId = null;
        }
    }

    // =========================================================================
    // Test 1: user WITH tenant_admin -> hasRole("tenant_admin") = true
    // =========================================================================

    @Test
    @DisplayName("hasRole returns true when user holds tenant_admin in the tenant")
    void hasRole_returnsTrue_whenUserHasTenantAdmin() {
        tenantId = TestIdGenerator.uniqueTenantId();
        seedTenantAdminRole(tenantId, testUser.getId());

        boolean result = adminRoleChecker.hasRole(tenantId, testUser.getId(), "tenant_admin");

        assertThat(result)
                .as("user with active tenant_admin role should return true")
                .isTrue();
    }

    // =========================================================================
    // Test 2: user WITH tenant_admin only -> hasRole("platform_admin") = false
    // =========================================================================

    @Test
    @DisplayName("hasRole returns false when user holds tenant_admin but checks platform_admin")
    void hasRole_returnsFalse_whenUserLacksRole() {
        tenantId = TestIdGenerator.uniqueTenantId();
        seedTenantAdminRole(tenantId, testUser.getId());

        boolean result = adminRoleChecker.hasRole(tenantId, testUser.getId(), "platform_admin");

        assertThat(result)
                .as("user with tenant_admin only should return false for platform_admin check")
                .isFalse();
    }

    // =========================================================================
    // Test 3: hasRole with a completely unknown role code -> false
    // =========================================================================

    @Test
    @DisplayName("hasRole returns false for a nonexistent role code")
    void hasRole_returnsFalse_forUnknownRoleCode() {
        tenantId = TestIdGenerator.uniqueTenantId();
        seedTenantAdminRole(tenantId, testUser.getId());

        boolean result = adminRoleChecker.hasRole(tenantId, testUser.getId(), "nonexistent_role");

        assertThat(result)
                .as("unknown role code should always return false")
                .isFalse();
    }

    // =========================================================================
    // Test 4: second call hits Caffeine cache — JDBC called only once
    // =========================================================================

    @Test
    @DisplayName("hasRole second call hits cache and skips JDBC")
    void hasRole_secondCallHitsCache_skipsJdbc() {
        tenantId = TestIdGenerator.uniqueTenantId();
        seedTenantAdminRole(tenantId, testUser.getId());

        // Clear spy invocations accumulated during seed writes so only the
        // hasRole() calls are counted below.
        Mockito.clearInvocations(jdbc);

        // When: same (tenantId, userId, roleCode) triple called twice
        boolean r1 = adminRoleChecker.hasRole(tenantId, testUser.getId(), "tenant_admin");
        boolean r2 = adminRoleChecker.hasRole(tenantId, testUser.getId(), "tenant_admin");

        // Then: both return true and the COUNT query hits JDBC exactly once
        assertThat(r1).as("first call should return true").isTrue();
        assertThat(r2).as("second call should return true (from cache)").isTrue();

        Mockito.verify(jdbc, Mockito.times(1))
                .queryForObject(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.eq(Long.class),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Seeds the minimum rows needed for a tenant_admin grant:
     * ab_tenant → ab_role(tenant_admin) → ab_tenant_member → ab_user_role.
     */
    private void seedTenantAdminRole(Long tenantId, Long userId) {
        long roleId = System.nanoTime() & 0x7fffffffffffffffL;
        long memberId = (System.nanoTime() ^ 0xdeadL) & 0x7fffffffffffffffL;

        jdbc.update(
                "INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) " +
                "VALUES (?, ?, ?, 'active', FALSE) ON CONFLICT (id) DO NOTHING",
                tenantId, "tn_" + tenantId, "arc_test_" + tenantId);

        jdbc.update(
                "INSERT INTO ab_role (id, pid, tenant_id, name, code, status, deleted_flag) " +
                "VALUES (?, ?, ?, ?, 'tenant_admin', 'active', FALSE)",
                roleId, "role_" + roleId, tenantId, "Tenant Admin " + roleId);

        jdbc.update(
                "INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) " +
                "VALUES (?, ?, ?, ?, 'active', FALSE)",
                memberId, "mem_" + memberId, tenantId, userId);

        jdbc.update(
                "INSERT INTO ab_user_role (id, pid, member_id, tenant_id, role_id, status, deleted_flag) " +
                "VALUES (?, ?, ?, ?, ?, 'active', FALSE)",
                System.nanoTime() & 0x7fffffffffffffffL,
                "ur_" + roleId, memberId, tenantId, roleId);
    }
}
