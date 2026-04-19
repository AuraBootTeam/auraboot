package com.auraboot.framework.application.security;

import com.auraboot.framework.permission.enums.RoleCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared helper that answers "does {@code userId} hold {@code roleCode} in {@code tenantId}?".
 *
 * <p>Consolidates the role-lookup SQL previously duplicated in
 * {@code UserSoulProfileAdminController.guardTenantAdmin()} so both
 * {@link AdminRoleInterceptor} and any future programmatic check share one
 * source of truth.
 *
 * <p>Join path follows the Phase-2 RBAC schema:
 * <pre>ab_tenant_member (user_id → id) → ab_user_role (member_id) → ab_role (code)</pre>
 *
 * <p>No caching: admin endpoints are low-QPS and freshness matters more than
 * latency. Revisit if traffic patterns change.
 */
@Component
@RequiredArgsConstructor
public class AdminRoleChecker {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @return {@code true} when an active, non-deleted {@code ab_user_role} row
     * binds {@code userId} (via {@code ab_tenant_member}) to an active,
     * non-deleted role with {@code code = roleCode} in {@code tenantId}.
     */
    public boolean hasRole(Long tenantId, Long userId, String roleCode) {
        if (tenantId == null || userId == null || roleCode == null) {
            return false;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user_role ur " +
                        " JOIN ab_tenant_member tm ON ur.member_id = tm.id " +
                        " JOIN ab_role r ON ur.role_id = r.id " +
                        " WHERE tm.user_id = ? " +
                        "   AND ur.tenant_id = ? " +
                        "   AND r.code = ? " +
                        "   AND (ur.deleted_flag = FALSE OR ur.deleted_flag IS NULL) " +
                        "   AND ur.status = 'active' " +
                        "   AND (r.deleted_flag = FALSE OR r.deleted_flag IS NULL) " +
                        "   AND r.status = 'active'",
                Long.class, userId, tenantId, roleCode);
        return count != null && count > 0;
    }

    /** Convenience: {@link #hasRole(Long, Long, String)} with {@link RoleCodes#TENANT_ADMIN}. */
    public boolean isTenantAdmin(Long tenantId, Long userId) {
        return hasRole(tenantId, userId, RoleCodes.TENANT_ADMIN);
    }
}
