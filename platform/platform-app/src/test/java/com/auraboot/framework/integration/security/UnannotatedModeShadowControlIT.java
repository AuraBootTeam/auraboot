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
 * Shadow-side control for the staged default-deny flip: the SAME probe request that
 * {@link UnannotatedModeFlipDenyIT} proves is rejected 403 under {@code deny} still succeeds
 * (200) under explicit {@code shadow} — pinning that the configured mode, not anything about
 * the probe endpoint, decides the outcome.
 */
@org.springframework.test.context.TestPropertySource(properties = "aura.security.authz.unannotated-mode=shadow")
@org.springframework.test.context.ActiveProfiles("authz-flip-test")
class UnannotatedModeShadowControlIT extends BaseIntegrationTest {

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
        sessions.createSession(testUser.getId(), token, "127.0.0.1", "authz-flip-shadow-it");
        clearIdentity();
    }

    @Test
    void shadowModeStillAllowsUnannotatedHandlerForAuthenticatedCaller() throws Exception {
        mvc.perform(post("/api/authz-flip/unannotated")
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
