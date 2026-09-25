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
@org.springframework.test.context.ActiveProfiles("platform-registry-auth-test")
class PlatformReleaseAuthenticationIT extends BaseIntegrationTest {
    @org.springframework.context.annotation.Profile("platform-registry-auth-test")
    @org.springframework.boot.test.context.TestConfiguration
    static class RegistryTestConnection {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.auraboot.framework.application.release.PlatformReleaseRegistrationService testPlatformRegistrationService(
                JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper mapper) {
            return new com.auraboot.framework.application.release.PlatformReleaseRegistrationService(jdbc, mapper);
        }
    }

    @Autowired WebApplicationContext web;
    @Autowired JwtAuthenticationFilter filter;
    @Autowired JwtUtil jwt;
    @Autowired UserDetailsService users;
    @Autowired SessionManagementService sessions;
    @Autowired AdminRoleChecker roles;
    @Autowired JdbcTemplate jdbc;

    @Test void authenticatedPlatformAdminRegistersWhileOtherCallersCannot() throws Exception {
        MockMvc mvc = webAppContextSetup(web).addFilters(filter).build();
        String code = "auth-it-" + UUID.randomUUID().toString();
        String uri = "/api/admin/platform-releases/" + code;
        String payload = """
                {"version":"1.0.0","sourceLockIdentity":"sha256:%s",
                 "platformContracts":{"runtime":["v1"],"pluginApi":["v1"],"dslSchema":[1]},
                 "artifacts":[{"type":"runtime","id":"core","version":"1.0.0",
                   "uri":"artifact:core/1.0.0","digest":"sha256:%s",
                   "source":{"repository":"core","commit":"%s"}}]}
                """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(40));
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
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_platform_release_registry WHERE platform_code=?", Integer.class, code));
            long tenantRoleId = AdminGuardTestSupport.grantTenantAdmin(jdbc, testTenant.getId(), testUser.getId());
            roles.invalidateAll();
            clearIdentity();
            mvc.perform(post(uri).header("Authorization", "Bearer " + token)
                    .contentType("application/json").accept("application/json").content(payload))
                    .andExpect(jsonPath("$.code").value("409"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_platform_release_registry WHERE platform_code=?", Integer.class, code));
            jdbc.update("UPDATE ab_role SET status='inactive' WHERE id=?", tenantRoleId);
            long roleId = AdminGuardTestSupport.grantPlatformAdmin(jdbc, testTenant.getId(), testUser.getId());
            roles.invalidateAll();
            clearIdentity();
            mvc.perform(post(uri).header("Authorization", "Bearer " + token)
                    .contentType("application/json").accept("application/json").content(payload))
                    .andDo(result -> assertTrue(result.getResponse().getContentAsString().contains("\"code\":\"0\""), result.getResponse().getContentAsString()))
                    .andExpect(jsonPath("$.code").value("0"))
                    .andExpect(jsonPath("$.data.registeredBy").value("user:" + testTenant.getId() + ":" + testUser.getId()));
            for (String invalid : java.util.List.of(
                    payload.replace("\"version\":\"1.0.0\"", "\"version\":\"latest\""),
                    payload.replace("\"dslSchema\":[1]", "\"dslSchema\":[0]"),
                    payload.replace("\"runtime\":[\"v1\"]", "\"runtime\":[\"v1\",\"v1\"]"),
                    payload.replace("\"pluginApi\":[\"v1\"]", "\"pluginApi\":[]"),
                    payload.replace("a".repeat(64), "bad-lock"),
                    payload.replace("c".repeat(40), "bad-commit"),
                    payload.replace("\"type\":\"runtime\"", "\"type\":\"unknown\""),
                    payload.replace("artifact:core/1.0.0", "file:/tmp/core"),
                    payload.replace("\"id\":\"core\"", "\"id\":42"),
                    payload.replace("{\"version\"", "{\"registeredBy\":\"user:forged\",\"version\""),
                    payload.replace("{\"version\"", "{\"version\":\"duplicate\",\"version\""),
                    payload + " {}", "null")) {
                assertNotEquals(payload, invalid);
                clearIdentity();
                mvc.perform(post(uri).header("Authorization", "Bearer " + token)
                        .contentType("application/json").accept("application/json").content(invalid))
                        .andDo(result -> assertTrue(result.getResponse().getContentAsString().contains("\"code\":\"400\""), "Rejected input: " + invalid + " Response: " + result.getResponse().getContentAsString()))
                        .andExpect(jsonPath("$.code").value("400"));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_platform_release_registry WHERE platform_code=?", Integer.class, code));
            }
            String releaseId = jdbc.queryForObject("SELECT release_id FROM ab_platform_release_registry WHERE platform_code=?", String.class, code);
            clearIdentity();
            mvc.perform(post(uri).header("Authorization", "Bearer " + token)
                    .contentType("application/json").accept("application/json").content(payload))
                    .andDo(result -> assertTrue(result.getResponse().getContentAsString().contains("\"code\":\"0\""), result.getResponse().getContentAsString()))
                    .andExpect(jsonPath("$.code").value("0"))
                    .andExpect(jsonPath("$.data.releaseId").value(releaseId));
            clearIdentity();
            mvc.perform(post(uri).header("Authorization", "Bearer " + token)
                    .contentType("application/json").accept("application/json").content(payload.replace("b".repeat(64), "d".repeat(64))))
                    .andExpect(jsonPath("$.code").value("409"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_platform_release_registry WHERE platform_code=?", Integer.class, code));
            jdbc.update("UPDATE ab_role SET status='inactive' WHERE id=?", roleId);
            roles.invalidateAll();
            clearIdentity();
            mvc.perform(post(uri).header("Authorization", "Bearer " + token)
                    .contentType("application/json").accept("application/json").content(payload))
                    .andExpect(jsonPath("$.code").value("409"));
            jdbc.update("UPDATE ab_role SET status='active' WHERE id=?", roleId);
            roles.invalidateAll();
            clearIdentity();
            String coordinateConflict = payload.replaceFirst("1\\.0\\.0", "2.0.0").replace("b".repeat(64), "e".repeat(64));
            mvc.perform(post(uri).header("Authorization", "Bearer " + token)
                    .contentType("application/json").accept("application/json").content(coordinateConflict))
                    .andExpect(jsonPath("$.code").value("409"))
                    .andExpect(jsonPath("$.message").value("Platform artifact coordinate already registered with different content"));
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
