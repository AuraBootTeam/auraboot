package com.auraboot.framework.application.security;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import com.auraboot.framework.integration.security.AdminGuardTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Path-scope role decision tests for {@link AdminRoleInterceptor}.
 *
 * <p>Verifies that {@code /api/admin/infrastructure/**} and
 * {@code /api/admin/cloud-config/**} require {@code platform_admin}, while all
 * other {@code /api/admin/**} paths (e.g. {@code /api/admin/users}) still
 * require {@code tenant_admin} — and that the two roles are <em>disjoint</em>:
 * holding one does not grant access to the other's paths.
 *
 * <p>Probe endpoints chosen for deterministic GET semantics (no body required):
 * <ul>
 *   <li>{@code /api/admin/infrastructure/status} — InfrastructureController</li>
 *   <li>{@code /api/admin/users/search} — AdminUserController</li>
 *   <li>{@code /api/admin/cloud-config} — CloudConfigController (list)</li>
 * </ul>
 *
 * <p>Test T3 and T4 seed a {@code platform_admin} role row via JDBC using the
 * same schema shape as the tenant_admin seeding helpers. The interceptor checks
 * role codes from the {@code ab_role} table, so no special schema migration is
 * required beyond inserting a row with {@code code='platform_admin'}.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("AdminRoleInterceptor — path-scope role decision (platform_admin vs tenant_admin)")
class AdminRoleInterceptorPathScopeIntegrationTest extends BaseIntegrationTest {

    /** A GET that hits InfrastructureController (platform_admin required path). */
    private static final String INFRA_URL = "/api/admin/infrastructure/status";

    /** A GET that hits AdminUserController (tenant_admin required path). */
    private static final String USERS_URL = "/api/admin/users/search";

    /** A GET that hits CloudConfigController (platform_admin required path, cloud-config subtree). */
    private static final String CLOUD_URL = "/api/admin/cloud-config";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminRoleChecker adminRoleChecker;

    private Long tenantId;

    @AfterEach
    void cleanup() {
        adminRoleChecker.invalidateAll();
        if (tenantId != null) {
            AdminGuardTestSupport.cleanupTenant(jdbc, tenantId);
        }
    }

    private MockMvc mockMvc() {
        return AdminGuardTestSupport.buildMockMvc(
                webApplicationContext,
                tenantId,
                testUser.getId(),
                testUser.getPid(),
                testUser.getUserName());
    }

    // =========================================================================
    // T1: tenant_admin user can reach /api/admin/users
    // =========================================================================

    @Test
    @DisplayName("T1: tenant_admin passes through on /api/admin/users (tenant-admin path)")
    void tenantAdmin_canAccess_userListEndpoint() throws Exception {
        tenantId = TestIdGenerator.uniqueTenantId();
        AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());

        // The interceptor should allow the request; AdminUserController returns 0 or
        // "0"-code JSON even with no params (search returns an empty list).
        mockMvc().perform(get(USERS_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
    }

    // =========================================================================
    // T2: tenant_admin user is rejected on /api/admin/infrastructure/**
    //     (platform_admin required — roles are disjoint)
    // =========================================================================

    @Test
    @DisplayName("T2: tenant_admin is rejected on /api/admin/infrastructure/** (disjoint roles)")
    void tenantAdmin_isRejected_onInfrastructureEndpoint() throws Exception {
        tenantId = TestIdGenerator.uniqueTenantId();
        AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());

        mockMvc().perform(get(INFRA_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));
    }

    // =========================================================================
    // T3: platform_admin user can reach /api/admin/infrastructure/**
    // =========================================================================

    @Test
    @DisplayName("T3: platform_admin passes through on /api/admin/infrastructure/**")
    void platformAdmin_canAccess_infrastructureEndpoint() throws Exception {
        tenantId = TestIdGenerator.uniqueTenantId();
        AdminGuardTestSupport.grantPlatformAdmin(jdbc, tenantId, testUser.getId());

        // The interceptor allows; InfrastructureController.status() always returns code "0".
        mockMvc().perform(get(INFRA_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
    }

    // =========================================================================
    // T4: platform_admin user is rejected on /api/admin/users
    //     (tenant_admin required — roles are disjoint)
    // =========================================================================

    @Test
    @DisplayName("T4: platform_admin is rejected on /api/admin/users (disjoint roles)")
    void platformAdmin_isRejected_onUserListEndpoint() throws Exception {
        tenantId = TestIdGenerator.uniqueTenantId();
        AdminGuardTestSupport.grantPlatformAdmin(jdbc, tenantId, testUser.getId());

        mockMvc().perform(get(USERS_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));
    }

    // =========================================================================
    // T5 (bonus): tenant_admin rejected on /api/admin/cloud-config (same pattern)
    // =========================================================================

    @Test
    @DisplayName("T5: tenant_admin is rejected on /api/admin/cloud-config (platform_admin required)")
    void tenantAdmin_isRejected_onCloudConfigEndpoint() throws Exception {
        tenantId = TestIdGenerator.uniqueTenantId();
        AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());

        mockMvc().perform(get(CLOUD_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));
    }
}
