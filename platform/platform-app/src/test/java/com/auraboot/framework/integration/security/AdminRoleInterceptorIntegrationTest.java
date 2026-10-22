package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.auraboot.framework.integration.TestIdGenerator;


/**
 * AdminRoleInterceptor — design doc 2026-04-19 §6.
 *
 * <p>Covers the six cases required by the design doc:
 * <ol>
 *   <li>admin → pass</li>
 *   <li>authenticated non-admin → 409</li>
 *   <li>no JWT → filter layer 401 (interceptor not reached) — asserted via
 *       absent MetaContext below, which surfaces as 409 because MockMvc doesn't
 *       run JwtAuthenticationFilter. The 401 layer is covered by the real
 *       filter's own tests.</li>
 *   <li>MetaContext missing tenantId/userId → 409</li>
 *   <li>role status != 'active' → 409</li>
 *   <li>role deleted_flag = TRUE → 409</li>
 * </ol>
 *
 * <p>Uses {@code /api/admin/user-soul-profiles/stats} as the probe endpoint
 * because (a) it is a GET with stable semantics, (b) it already exists, and
 * (c) when admin is granted it deterministically returns code "0" even with
 * zero rows.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("AdminRoleInterceptor (platform /api/admin/** guard)")
class AdminRoleInterceptorIntegrationTest extends BaseIntegrationTest {

    private static final String PROBE_URL = "/api/admin/user-soul-profiles/stats";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            AdminGuardTestSupport.cleanupTenant(jdbc, tenantId);
        }
    }

    private MockMvc mockMvcWithCurrentUser() {
        return AdminGuardTestSupport.buildMockMvc(
                webApplicationContext,
                tenantId,
                testUser.getId(),
                testUser.getPid(),
                testUser.getUserName());
    }

    private MockMvc mockMvcWithEmptyContext() {
        Filter metaFilter = (request, response, chain) -> {
            MetaContext.clear();
            chain.doFilter(request, response);
        };
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(metaFilter, "/*").build();
    }

    private void freshTenant() {
        tenantId = TestIdGenerator.uniqueTenantId();
    }

    // =======================================================================
    // (a) admin → pass
    // =======================================================================
    @Test
    @DisplayName("admin holding tenant_admin passes through to the controller")
    void adminPass() throws Exception {
        freshTenant();
        AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());

        mockMvcWithCurrentUser().perform(get(PROBE_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
    }

    // =======================================================================
    // (b) authenticated non-admin → 409
    // =======================================================================
    @Test
    @DisplayName("authenticated non-admin receives 409 admin role required")
    void nonAdminDenied() throws Exception {
        freshTenant();
        // No grant — the MetaContext carries an authenticated user but no role.

        mockMvcWithCurrentUser().perform(get(PROBE_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));
    }

    // =======================================================================
    // (d) missing MetaContext (tenantId/userId null) → 409
    // =======================================================================
    @Test
    @DisplayName("missing MetaContext (no tenantId/userId) returns 409")
    void missingMetaContextDenied() throws Exception {
        mockMvcWithEmptyContext()
                .perform(get(PROBE_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));
    }

    // =======================================================================
    // (e) role status != 'active' → 409
    // =======================================================================
    @Test
    @DisplayName("role exists but status!='active' -> 409")
    void inactiveRoleDenied() throws Exception {
        freshTenant();
        long roleId = AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());
        jdbc.update("UPDATE ab_role SET status = 'disabled' WHERE id = ?", roleId);

        mockMvcWithCurrentUser().perform(get(PROBE_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("409"));
    }

    // =======================================================================
    // (f) role deleted_flag=TRUE → 409
    // =======================================================================
    @Test
    @DisplayName("role exists but deleted_flag=TRUE -> 409")
    void softDeletedRoleDenied() throws Exception {
        freshTenant();
        long roleId = AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());
        jdbc.update("UPDATE ab_role SET deleted_flag = TRUE WHERE id = ?", roleId);

        mockMvcWithCurrentUser().perform(get(PROBE_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("409"));
    }

    // =======================================================================
    // Extra: ur.deleted_flag=TRUE also denied (symmetry with role flag)
    // =======================================================================
    @Test
    @DisplayName("user-role binding deleted_flag=TRUE -> 409")
    void softDeletedBindingDenied() throws Exception {
        freshTenant();
        AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());
        jdbc.update("UPDATE ab_user_role SET deleted_flag = TRUE WHERE tenant_id = ?", tenantId);

        mockMvcWithCurrentUser().perform(get(PROBE_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("409"));
    }
}
