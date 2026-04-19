package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.tenant.MetaContext;
import jakarta.servlet.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Shared helpers for the per-controller {@code *AdminGuardIntegrationTest}
 * suite and {@link AdminRoleInterceptorIntegrationTest}.
 *
 * <p>Each guard test needs two MockMvc variants:
 * <ul>
 *   <li>one where the current user holds {@code tenant_admin} (not used for
 *       the minimal non-admin→409 suite but kept here for admin-200 smokes);</li>
 *   <li>one where the current user is an authenticated non-admin (drives the
 *       409 assertion).</li>
 * </ul>
 *
 * <p>The MockMvc filter stack re-installs {@link MetaContext} per request
 * because {@code MockMvc} runs the interceptor chain but not the real
 * {@code JwtAuthenticationFilter}.
 */
public final class AdminGuardTestSupport {

    private AdminGuardTestSupport() {}

    public static MockMvc buildMockMvc(WebApplicationContext ctx,
                                       Long tenantId, Long userId,
                                       String userPid, String userName) {
        Filter metaFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(tenantId, userId, userPid, userName);
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
            }
        };
        return MockMvcBuilders.webAppContextSetup(ctx).addFilter(metaFilter, "/*").build();
    }

    /** Inserts ab_tenant + ab_role(tenant_admin) + ab_tenant_member + ab_user_role rows. */
    public static long grantTenantAdmin(JdbcTemplate jdbc, Long tenantId, Long userId) {
        long roleId = System.nanoTime() & 0x7fffffffffffffffL;
        long memberId = (System.nanoTime() ^ 0xabcdL) & 0x7fffffffffffffffL;
        jdbc.update("INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) " +
                        "VALUES (?, ?, ?, 'active', FALSE) ON CONFLICT (id) DO NOTHING",
                tenantId, "tn_" + tenantId, "guard_test_" + tenantId);
        jdbc.update("INSERT INTO ab_role (id, pid, tenant_id, name, code, status, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, 'tenant_admin', 'active', FALSE)",
                roleId, "role_admin_" + roleId, tenantId, "Tenant Admin " + roleId);
        jdbc.update("INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, 'active', FALSE)",
                memberId, "mem_" + memberId, tenantId, userId);
        jdbc.update("INSERT INTO ab_user_role (id, pid, member_id, tenant_id, role_id, status, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, ?, 'active', FALSE)",
                System.nanoTime() & 0x7fffffffffffffffL,
                "ur_" + roleId, memberId, tenantId, roleId);
        return roleId;
    }

    public static void cleanupTenant(JdbcTemplate jdbc, Long tenantId) {
        jdbc.update("DELETE FROM ab_user_role WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_role WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_tenant_member WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_tenant WHERE id = ? AND name LIKE 'guard_test_%'", tenantId);
    }
}
