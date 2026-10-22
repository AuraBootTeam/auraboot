package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.saas.controller.PlatformStatsController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REG-2 regression guard (DDR-2026-06-30-quote-bom-rbac-capability-model-endstate):
 * {@link PlatformStatsController} exposes cross-tenant platform stats + the full tenant
 * registry under {@code /api/platform}. It is NOT under {@code /api/admin/**} and declared
 * no {@code @RequirePermission}, so the PermissionInterceptor's default {@code shadow} mode
 * used to let ANY authenticated tenant member enumerate every tenant (verified live: a
 * zero-role user got HTTP 200). The fix adds an explicit {@code platform_admin} guard
 * (mirroring {@code CrossTenantGrantController#guardPlatformAdmin}); this IT locks it.
 *
 * <p>Cases:
 * <ul>
 *   <li>A — non-platform-admin: {@code /stats} and {@code /tenants} return a 403 envelope.</li>
 *   <li>B — platform admin: both succeed (code 0).</li>
 * </ul>
 *
 * <p>Direct controller invocation + ApiResponse envelope assertion (the guard returns
 * {@code ApiResponse.error(403,...)} inside an HTTP 200 envelope), matching
 * {@code CrossTenantGrantControllerIntegrationTest}.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("PlatformStatsController platform_admin guard (REG-2)")
class PlatformStatsControllerAuthIT extends BaseIntegrationTest {

    @Autowired private PlatformStatsController controller;
    @Autowired private AdminRoleChecker adminRoleChecker;
    @Autowired private JdbcTemplate jdbc;

    private Long platformAdminRoleId;
    private boolean roleWasPreexisting;

    @BeforeEach
    void setup() {
        // Caller MetaContext = test user in test tenant (a normal, non-platform-admin member).
        MetaContext.setContext(testTenant.getId(), testUser.getId(),
                testUser.getPid(), testUser.getUserName());
        // Ensure no cached platform_admin verdict leaks in from a prior test in this JVM.
        adminRoleChecker.invalidateAll();
    }

    @AfterEach
    void cleanup() {
        if (platformAdminRoleId != null) {
            jdbc.update("DELETE FROM ab_user_role WHERE member_id = ? AND role_id = ? AND tenant_id = ?",
                    testTenantMember.getId(), platformAdminRoleId, testTenant.getId());
            if (!roleWasPreexisting) {
                jdbc.update("DELETE FROM ab_role WHERE id = ?", platformAdminRoleId);
            }
            platformAdminRoleId = null;
            roleWasPreexisting = false;
        }
        adminRoleChecker.invalidateAll();
        MetaContext.clear();
    }

    /** Grant testUser the platform_admin role in the test tenant (idempotent). */
    private void grantPlatformAdmin() {
        java.util.List<Long> existing = jdbc.queryForList(
                "SELECT id FROM ab_role WHERE tenant_id = ? AND code = ? "
                        + "AND (deleted_flag = false OR deleted_flag IS NULL) LIMIT 1",
                Long.class, testTenant.getId(), RoleCodes.PLATFORM_ADMIN);
        if (!existing.isEmpty()) {
            platformAdminRoleId = existing.get(0);
            roleWasPreexisting = true;
        } else {
            long roleId = (System.currentTimeMillis() << 12) | (System.nanoTime() & 0xFFF);
            jdbc.update(
                    "INSERT INTO ab_role (id, pid, name, code, type, scope_type, status, "
                            + " tenant_id, is_default, is_system, deleted_flag, priority, "
                            + " created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'system', 'global', 'active', ?, false, true, false, 1, now(), now())",
                    roleId, "rl_pa_" + System.nanoTime(), "platform_admin", RoleCodes.PLATFORM_ADMIN,
                    testTenant.getId());
            platformAdminRoleId = roleId;
            roleWasPreexisting = false;
        }
        Long alreadyBound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_user_role WHERE member_id = ? AND role_id = ? "
                        + "AND tenant_id = ? AND status = 'active' "
                        + "AND (deleted_flag = false OR deleted_flag IS NULL)",
                Long.class, testTenantMember.getId(), platformAdminRoleId, testTenant.getId());
        if (alreadyBound == null || alreadyBound == 0L) {
            long urId = (System.currentTimeMillis() << 12) | ((System.nanoTime() + 1) & 0xFFF);
            jdbc.update("INSERT INTO ab_user_role (id, pid, member_id, role_id, tenant_id, "
                            + " status, deleted_flag, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'active', false, now(), now())",
                    urId, "ur_pa_" + System.nanoTime(), testTenantMember.getId(),
                    platformAdminRoleId, testTenant.getId());
        }
        adminRoleChecker.invalidateAll();
    }

    @Test
    @DisplayName("A: non-platform-admin gets 403 on /stats and /tenants")
    void caseA_non_platform_admin_denied() {
        ApiResponse<Map<String, Object>> stats = controller.getStats();
        assertThat(stats.getCode()).isEqualTo("403");

        ApiResponse<?> tenants = controller.listTenants();
        assertThat(tenants.getCode()).isEqualTo("403");
    }

    @Test
    @DisplayName("B: platform admin can read /stats and /tenants")
    void caseB_platform_admin_allowed() {
        grantPlatformAdmin();

        ApiResponse<Map<String, Object>> stats = controller.getStats();
        assertThat(stats.getCode()).isEqualTo("0");
        assertThat(stats.getData()).containsKey("tenantCount");

        ApiResponse<?> tenants = controller.listTenants();
        assertThat(tenants.getCode()).isEqualTo("0");
        assertThat(tenants.getData()).isNotNull();
    }
}
