package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.web.filter.JwtAuthenticationFilter;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Deny-side evidence for the staged default-deny flip (unannotated-mode=deny): with the flip
 * engaged, an unannotated handler is rejected 403 for an authenticated caller while the
 * {@code @AuthenticatedAccess} probe counterpart stays reachable (200) and an unauthenticated
 * caller is rejected 401 by the JWT filter — the full 401/403/200 matrix on real MVC, DB
 * session validation and interceptor wiring (same harness as the release auth ITs).
 */
@org.springframework.test.context.TestPropertySource(properties = "aura.security.authz.unannotated-mode=deny")
@org.springframework.test.context.ActiveProfiles("authz-flip-test")
class UnannotatedModeFlipDenyIT extends BaseIntegrationTest {

    @Autowired WebApplicationContext web;
    @Autowired JwtAuthenticationFilter filter;
    @Autowired JwtUtil jwt;
    @Autowired UserDetailsService users;
    @Autowired SessionManagementService sessions;

    private MockMvc mvc;
    private String token;

    @BeforeEach
    void setUpMockMvcAndSession() {
        mvc = webAppContextSetup(web).addFilters(filter).build();
        token = jwt.generateTokenWithTenantId(users.loadUserByUsername(testUser.getPid()),
                testUser.getPid(), testTenant.getId(), testTenantMember.getId(),
                testUser.getSecurityVersion() == null ? 0 : testUser.getSecurityVersion());
        sessions.createSession(testUser.getId(), token, "127.0.0.1", "authz-flip-deny-it");
        clearIdentity();
    }

    @Test
    void unauthenticatedCallerIsRejectedByTheJwtFilter() throws Exception {
        mvc.perform(post("/api/authz-flip/unannotated")
                        .contentType("application/json").accept("application/json"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/authz-flip/authenticated-access")
                        .contentType("application/json").accept("application/json"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void denyModeRejectsUnannotatedHandlerForAuthenticatedCaller() throws Exception {
        mvc.perform(post("/api/authz-flip/unannotated")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").accept("application/json"))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedAccessMarkerStaysReachableUnderDeny() throws Exception {
        mvc.perform(post("/api/authz-flip/authenticated-access")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").value("reached"));
    }

    private static void clearIdentity() {
        SecurityContextHolder.clearContext();
        MetaContext.clear();
    }
}
