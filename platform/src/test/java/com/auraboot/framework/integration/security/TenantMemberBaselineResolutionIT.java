package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 baseline end-to-end guard (DDR-2026-06-30, 做法 B step 2). Proves the REAL-DB round-trip that
 * the unit test (mocked mappers) cannot: a member with NO {@code ab_user_role} rows still resolves
 * the {@code tenant_member} baseline role's permissions via {@link UserPermissionService}, purely
 * because the baseline role exists for the tenant (implicit union in
 * {@code UserPermissionServiceImpl.getUserPermissionIds}). This is what fixes the original incident
 * (business roles / roleless members could not render pages) without any per-user backfill.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("tenant_member baseline resolves for a role-less member (L1 做法 B)")
class TenantMemberBaselineResolutionIT extends BaseIntegrationTest {

    @Autowired private UserPermissionService userPermissionService;
    @Autowired private JdbcTemplate jdbc;

    private Long baselineRoleId;
    private Long boundPermissionId;
    private Long bindingId;
    private final Long roleLessUserId = 778_899_001L;
    private final Long roleLessMemberId = 778_899_002L;

    @BeforeEach
    void setup() {
        // A real, existing permission to bind the baseline role to (any perm; FK-safe).
        boundPermissionId = jdbc.queryForObject(
                "SELECT id FROM ab_permission WHERE (deleted_flag = false OR deleted_flag IS NULL) ORDER BY id LIMIT 1",
                Long.class);
        assertThat(boundPermissionId).as("test needs at least one registered permission").isNotNull();

        // Seed the tenant_member baseline role in the test tenant (mirrors CrossTenant harness).
        baselineRoleId = (System.currentTimeMillis() << 12) | (System.nanoTime() & 0xFFF);
        jdbc.update(
                "INSERT INTO ab_role (id, pid, name, code, type, scope_type, status, tenant_id, "
                        + " is_default, is_system, deleted_flag, priority, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'tenant_member', 'tenant', 'tenant', 'active', ?, false, true, false, 15, now(), now())",
                baselineRoleId, "rl_tm_" + baselineRoleId, "租户成员基线", testTenant.getId());
        // Bind the baseline role -> the permission.
        bindingId = (System.currentTimeMillis() << 12) | ((System.nanoTime() + 7) & 0xFFF);
        jdbc.update(
                "INSERT INTO ab_role_permission (id, pid, tenant_id, role_id, permission_id, grant_type, "
                        + " status, deleted_flag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'grant', 'active', false, now(), now())",
                bindingId, "rp_tm_" + bindingId, testTenant.getId(), baselineRoleId, boundPermissionId);

        // Caller = a member with NO ab_user_role rows in this tenant.
        MetaContext.setContext(testTenant.getId(), roleLessUserId, "u-tm-pid", "tm-tester");
        MetaContext.setMemberId(roleLessMemberId);
        userPermissionService.evictUserPermissions(roleLessUserId); // guard against a stale cache entry
    }

    @AfterEach
    void cleanup() {
        userPermissionService.evictUserPermissions(roleLessUserId);
        if (bindingId != null) jdbc.update("DELETE FROM ab_role_permission WHERE id = ?", bindingId);
        if (baselineRoleId != null) jdbc.update("DELETE FROM ab_role WHERE id = ?", baselineRoleId);
        MetaContext.clear();
    }

    @Test
    @DisplayName("role-less member inherits tenant_member permissions with no ab_user_role row")
    void roleLessMember_inheritsBaseline() {
        Set<Long> perms = userPermissionService.getUserPermissionIds(roleLessUserId);
        assertThat(perms)
                .as("role-less member must inherit the tenant_member baseline permission implicitly")
                .contains(boundPermissionId);
    }
}
