package com.auraboot.framework.application.security;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    // MockitoSpyBean so cache tests can clear invocations and verify JDBC call count.
    // Delegates to the real bean for all seeding writes in existing tests.
    @MockitoSpyBean
    private JdbcTemplate jdbc;

    private Long tenantId;
    private Long ownedUserId;
    private long roleIdForCleanup;

    @AfterEach
    void cleanup() {
        // Invalidate cache so seeded keys from one test never bleed into the next.
        adminRoleChecker.invalidateAll();
        if (tenantId != null) {
            jdbc.update("DELETE FROM ab_user_role WHERE role_id = ?", roleIdForCleanup);
            jdbc.update("DELETE FROM ab_role WHERE id = ?", roleIdForCleanup);
            jdbc.update("DELETE FROM ab_tenant_member WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant WHERE id = ?", tenantId);
            tenantId = null;
        }
        if (ownedUserId != null) {
            jdbc.update("DELETE FROM ab_user WHERE id=?", ownedUserId);
            ownedUserId = null;
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

    @Test
    @DisplayName("Privileged checks immediately observe revocation without clearing a cache")
    void administratorRevocationIsEffectiveOnTheNextCheck() {
        tenantId = TestIdGenerator.uniqueTenantId();
        seedTenantAdminRole(tenantId, testUser.getId());
        assertThat(adminRoleChecker.hasRole(tenantId, testUser.getId(), "tenant_admin")).isTrue();
        jdbc.update("UPDATE ab_user_role SET status='inactive' WHERE tenant_id=?", tenantId);
        assertThat(adminRoleChecker.hasRole(tenantId, testUser.getId(), "tenant_admin")).isFalse();
        jdbc.update("UPDATE ab_user_role SET status='active' WHERE tenant_id=?", tenantId);
        assertThat(adminRoleChecker.hasRole(tenantId, testUser.getId(), "tenant_admin")).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "assignment-disabled", "assignment-deleted", "expired", "future", "member-disabled",
            "member-deleted", "user-disabled", "user-deleted", "role-disabled", "role-deleted",
            "assignment-foreign-tenant", "role-foreign-tenant"})
    void invalidSubjectsAndAssignmentsCannotRetainAdministration(String state) {
        tenantId = TestIdGenerator.uniqueTenantId();
        ownedUserId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO ab_user (id,pid,is_enabled,deleted_flag) VALUES (?,?,TRUE,FALSE)",
                ownedUserId, com.auraboot.framework.common.util.UniqueIdGenerator.generate());
        seedTenantAdminRole(tenantId, ownedUserId);
        assertThat(adminRoleChecker.hasRole(tenantId, ownedUserId, "tenant_admin")).isTrue();
        switch (state) {
            case "assignment-disabled" -> jdbc.update("UPDATE ab_user_role SET status='inactive' WHERE tenant_id=?", tenantId);
            case "assignment-deleted" -> jdbc.update("UPDATE ab_user_role SET deleted_flag=TRUE WHERE tenant_id=?", tenantId);
            case "expired" -> jdbc.update("UPDATE ab_user_role SET expiry_date=CURRENT_DATE-1 WHERE tenant_id=?", tenantId);
            case "future" -> jdbc.update("UPDATE ab_user_role SET effective_date=CURRENT_DATE+1 WHERE tenant_id=?", tenantId);
            case "member-disabled" -> jdbc.update("UPDATE ab_tenant_member SET status='inactive' WHERE tenant_id=?", tenantId);
            case "member-deleted" -> jdbc.update("UPDATE ab_tenant_member SET deleted_flag=TRUE WHERE tenant_id=?", tenantId);
            case "user-disabled" -> jdbc.update("UPDATE ab_user SET is_enabled=FALSE WHERE id=?", ownedUserId);
            case "user-deleted" -> jdbc.update("UPDATE ab_user SET deleted_flag=TRUE WHERE id=?", ownedUserId);
            case "role-disabled" -> jdbc.update("UPDATE ab_role SET status='inactive' WHERE tenant_id=?", tenantId);
            case "role-deleted" -> jdbc.update("UPDATE ab_role SET deleted_flag=TRUE WHERE tenant_id=?", tenantId);
            case "assignment-foreign-tenant" -> jdbc.update("UPDATE ab_user_role SET tenant_id=? WHERE tenant_id=?", getTestTenant().getId(), tenantId);
            case "role-foreign-tenant" -> jdbc.update("UPDATE ab_role SET tenant_id=? WHERE tenant_id=?", getTestTenant().getId(), tenantId);
            default -> throw new IllegalArgumentException(state);
        }
        assertThat(adminRoleChecker.hasRole(tenantId, ownedUserId, "tenant_admin")).as(state).isFalse();

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
        roleIdForCleanup = roleId;
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
