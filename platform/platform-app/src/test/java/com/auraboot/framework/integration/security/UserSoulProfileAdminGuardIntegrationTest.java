package com.auraboot.framework.integration.security;

import com.auraboot.framework.integration.BaseIntegrationTest;
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
import com.auraboot.framework.integration.TestIdGenerator;

/**
 * Round-trip verification that the USP admin controller now goes through the
 * platform-wide {@link com.auraboot.framework.application.security.AdminRoleInterceptor}
 * since the per-controller {@code guardTenantAdmin()} was removed on 2026-04-19.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("UserSoulProfileAdminController sits behind AdminRoleInterceptor")
class UserSoulProfileAdminGuardIntegrationTest extends BaseIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            AdminGuardTestSupport.cleanupTenant(jdbc, tenantId);
        }
    }

    @Test
    @DisplayName("non-admin GET /api/admin/user-soul-profiles -> 409")
    void nonAdminBlocked() throws Exception {
        tenantId = TestIdGenerator.uniqueTenantId();

        MockMvc mockMvc = AdminGuardTestSupport.buildMockMvc(
                webApplicationContext,
                tenantId,
                testUser.getId(),
                testUser.getPid(),
                testUser.getUserName());

        mockMvc.perform(get("/api/admin/user-soul-profiles").contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));
    }

    @Test
    @DisplayName("admin GET /api/admin/user-soul-profiles -> 200")
    void adminPasses() throws Exception {
        tenantId = TestIdGenerator.uniqueTenantId();
        AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());

        MockMvc mockMvc = AdminGuardTestSupport.buildMockMvc(
                webApplicationContext,
                tenantId,
                testUser.getId(),
                testUser.getPid(),
                testUser.getUserName());

        mockMvc.perform(get("/api/admin/user-soul-profiles").contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0"));
    }
}
