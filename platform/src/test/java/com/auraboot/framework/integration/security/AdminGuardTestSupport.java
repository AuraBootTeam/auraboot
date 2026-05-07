package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
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
        return grantRole(jdbc, tenantId, userId, "tenant_admin");
    }

    /**
     * Inserts ab_tenant + ab_role(platform_admin) + ab_tenant_member + ab_user_role rows.
     * Uses the same schema shape as {@link #grantTenantAdmin}; the role code differs.
     */
    public static long grantPlatformAdmin(JdbcTemplate jdbc, Long tenantId, Long userId) {
        return grantRole(jdbc, tenantId, userId, "platform_admin");
    }

    /**
     * Core seeding helper: ensures the tenant row exists then binds {@code userId}
     * to a fresh role with the given {@code roleCode}.
     *
     * <p>The method reuses an existing {@code ab_tenant_member} row for the
     * {@code (tenantId, userId)} pair when one already exists (guards against the
     * partial-unique-index {@code idx_ab_tenant_member_unique} on
     * {@code (tenant_id, user_id) WHERE deleted_flag = FALSE}).
     *
     * @return the generated role id (for ad-hoc status / deleted_flag mutations in tests)
     */
    public static long grantRole(JdbcTemplate jdbc, Long tenantId, Long userId, String roleCode) {
        long roleId = System.nanoTime() & 0x7fffffffffffffffL;

        // 1. Ensure tenant row exists
        jdbc.update("INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) " +
                        "VALUES (?, ?, ?, 'active', FALSE) ON CONFLICT (id) DO NOTHING",
                tenantId, "tn_" + tenantId, "guard_test_" + tenantId);

        // 2. Insert role with the requested code.
        //    pid is VARCHAR(26) — use UniqueIdGenerator (ULID, exactly 26 chars).
        //    name is VARCHAR(50) — keep it short.
        jdbc.update("INSERT INTO ab_role (id, pid, tenant_id, name, code, status, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, ?, 'active', FALSE)",
                roleId, UniqueIdGenerator.generate(), tenantId, roleCode, roleCode);

        // 3. Reuse or create the tenant_member row.
        //    A partial unique index exists on (tenant_id, user_id) WHERE deleted_flag=FALSE,
        //    so we probe first to avoid a constraint violation.
        //    Use query() + stream to handle zero-row result without EmptyResultDataAccessException.
        Long memberId = jdbc.query(
                "SELECT id FROM ab_tenant_member " +
                        "WHERE tenant_id = ? AND user_id = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                        "LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                tenantId, userId
        ).stream().findFirst().orElse(null);
        if (memberId == null) {
            memberId = (System.nanoTime() ^ 0xabcdL) & 0x7fffffffffffffffL;
            jdbc.update("INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) " +
                            "VALUES (?, ?, ?, ?, 'active', FALSE)",
                    memberId, UniqueIdGenerator.generate(), tenantId, userId);
        }

        // 4. Bind the new role to the member
        jdbc.update("INSERT INTO ab_user_role (id, pid, member_id, tenant_id, role_id, status, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, ?, 'active', FALSE)",
                System.nanoTime() & 0x7fffffffffffffffL,
                UniqueIdGenerator.generate(), memberId, tenantId, roleId);
        return roleId;
    }

    public static void cleanupTenant(JdbcTemplate jdbc, Long tenantId) {
        jdbc.update("DELETE FROM ab_user_role WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_role WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_tenant_member WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_tenant WHERE id = ? AND name LIKE 'guard_test_%'", tenantId);
    }
}
