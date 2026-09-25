package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.web.filter.JwtAuthenticationFilter;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real JWT validation, session lookup, database roles, MVC and registration persistence. */
@org.springframework.test.context.TestPropertySource(properties = "aura.security.authz.unannotated-mode=deny")
class ApplicationReleaseAuthenticationIT extends BaseIntegrationTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class RegistryTestConnection {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.auraboot.framework.application.release.ApplicationReleaseRegistrationService testRegistrationService(
                JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper mapper) {
            return new com.auraboot.framework.application.release.ApplicationReleaseRegistrationService(jdbc, mapper);
        }
    }

    @Autowired WebApplicationContext web;
    @Autowired JwtAuthenticationFilter filter;
    @Autowired JwtUtil jwt;
    @Autowired UserDetailsService users;
    @Autowired SessionManagementService sessions;
    @Autowired AdminRoleChecker roles;
    @Autowired JdbcTemplate jdbc;

    @Test void authenticatedPlatformAdminCreatesAndRegistersWhileOtherCallersCannot() throws Exception {
        MockMvc mvc = webAppContextSetup(web).addFilters(filter).build();
        String uri = "/api/admin/application-releases";
        String code = "auth-it-" + UUID.randomUUID().toString();
        String payload = "{\"code\":\"" + code + "\",\"name\":\"Auth integration application\"}";
        String token = jwt.generateTokenWithTenantId(users.loadUserByUsername(testUser.getPid()),
                testUser.getPid(), testTenant.getId(), testTenantMember.getId(),
                testUser.getSecurityVersion() == null ? 0 : testUser.getSecurityVersion());
        sessions.createSession(testUser.getId(), token, "127.0.0.1", "release-auth-it");
        roles.invalidateAll();
        try {
            clearIdentity();
            mvc.perform(post(uri).contentType("application/json").accept("application/json").content(payload))
                    .andExpect(status().isUnauthorized());
            clearIdentity();
            mvc.perform(post(uri).header("Authorization", "Bearer " + token)
                    .contentType("application/json").accept("application/json").content(payload))
                    .andExpect(jsonPath("$.code").value("409"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_application WHERE code=?", Integer.class, code));
            long roleId = AdminGuardTestSupport.grantPlatformAdmin(jdbc, testTenant.getId(), testUser.getId());
            roles.invalidateAll();
            clearIdentity();
            mvc.perform(post(uri).header("Authorization", "Bearer " + token)
                    .contentType("application/json").accept("application/json").content(payload))
                    .andExpect(jsonPath("$.code").value("0"))
                    .andExpect(jsonPath("$.data.createdBy").value("user:" + testTenant.getId() + ":" + testUser.getId()));
            String content = "{\"registrationKey\":\"auth-build\",\"content\":{\"compatibilityEpoch\":1,"
                    + "\"sourceLockIdentity\":\"sha256:" + "a".repeat(64) + "\",\"platformCompatibility\":{\"runtimeContract\":\"1\"},"
                    + "\"components\":[{\"key\":\"fixture\",\"type\":\"definition\",\"version\":\"1.0.0\",\"digest\":\"sha256:"
                    + "b".repeat(64) + "\"}]}}";
            clearIdentity();
            mvc.perform(post(uri + "/" + code).header("Authorization", "Bearer " + token)
                    .contentType("application/json").accept("application/json").content(content))
                    .andExpect(jsonPath("$.code").value("0"))
                    .andExpect(jsonPath("$.data.sequence").value(1));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_application_release r JOIN ab_application a ON a.id=r.application_id WHERE a.code=?", Integer.class, code));
            jdbc.update("UPDATE ab_role SET status='inactive' WHERE id=?", roleId);
            roles.invalidateAll();
            clearIdentity();
            mvc.perform(post(uri + "/" + code).header("Authorization", "Bearer " + token)
                    .contentType("application/json").accept("application/json").content(content))
                    .andExpect(jsonPath("$.code").value("409"));
        } finally {
            roles.invalidateAll();
            clearIdentity();
        }
    }
    private static void clearIdentity() {
        SecurityContextHolder.clearContext();
        MetaContext.clear();
    }
}
