package com.auraboot.framework.integration.security;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.integration.TestIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REG-5/6 regression guard (DDR-2026-06-30): role/permission ASSIGNMENT is restricted to
 * tenant_admin. The {@code PermissionInterceptor} requires tenant_admin (in addition to the coarse
 * {@code *_MANAGE} code) for the assignment codes, so a delegated non-admin holding
 * {@code USER_ROLE_MANAGE} cannot assign roles (which would let it self-escalate to tenant_admin).
 */
@DisplayName("role/permission assignment requires tenant_admin (REG-5/6)")
class AssignmentTenantAdminGuardIT extends BaseIntegrationTest {

    private static final String GATE_MESSAGE = "tenant_admin required for role/permission assignment";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            AdminGuardTestSupport.cleanupTenant(jdbc, tenantId);
            tenantId = null;
        }
    }

    @Test
    @DisplayName("non-admin holding USER_ROLE_MANAGE is denied (403 tenant_admin required)")
    void nonAdminWithManageCode_denied() throws Exception {
        tenantId = TestIdGenerator.uniqueTenantId();
        // A delegated non-admin role that carries USER_ROLE_MANAGE — but the user is NOT tenant_admin.
        long roleId = AdminGuardTestSupport.grantRole(jdbc, tenantId, testUser.getId(), "delegated_role_mgr");
        AdminGuardTestSupport.grantPermissionToRole(jdbc, tenantId, roleId,
                MetaPermission.USER_ROLE_MANAGE, "assign roles", "org", "user_role", "update");

        MockMvc mvc = AdminGuardTestSupport.buildMockMvc(webApplicationContext, tenantId,
                testUser.getId(), testUser.getPid(), testUser.getUserName());

        mvc.perform(post("/api/user-roles/assign")
                        .param("memberId", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("[1]"))
                // Proof this is the REG-5/6 gate (not a missing-permission 403): the user HOLDS
                // USER_ROLE_MANAGE (granted above), so without the tenant_admin gate this would pass.
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("tenant_admin passes the assignment gate (not blocked by the tenant_admin guard)")
    void tenantAdmin_passesGate() throws Exception {
        tenantId = TestIdGenerator.uniqueTenantId();
        // tenant_admin role + the USER_ROLE_MANAGE permission on it → passes both the gate and hasPermission.
        long adminRoleId = AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());
        AdminGuardTestSupport.grantPermissionToRole(jdbc, tenantId, adminRoleId,
                MetaPermission.USER_ROLE_MANAGE, "assign roles", "org", "user_role", "update");

        MockMvc mvc = AdminGuardTestSupport.buildMockMvc(webApplicationContext, tenantId,
                testUser.getId(), testUser.getPid(), testUser.getUserName());

        // The gate must NOT fire for tenant_admin (business outcome may vary, but never the gate deny).
        mvc.perform(post("/api/user-roles/assign")
                        .param("memberId", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("[1]"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(GATE_MESSAGE));
    }
}
