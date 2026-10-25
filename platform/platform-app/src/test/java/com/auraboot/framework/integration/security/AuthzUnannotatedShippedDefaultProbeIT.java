package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.web.filter.JwtAuthenticationFilter;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Pins the SHIPPED default of the un-annotated handler surface through the real MVC +
 * security filter chain: with no property override, {@code aura.security.authz.
 * unannotated-mode} defaults to {@code deny}, so a handler carrying neither
 * {@code @RequirePermission} nor {@code @AuthenticatedAccess} is rejected with HTTP 403
 * while an explicitly acknowledged {@code @AuthenticatedAccess} handler passes. Unlike
 * {@link AuthzUnannotatedDefaultDenyProbeIT} this test overrides nothing — if someone
 * reverts the shipped default to {@code shadow}, this test fails first.
 */
@Import({AuthzUnannotatedShippedDefaultProbeIT.UnannotatedProbeController.class,
        AuthzUnannotatedShippedDefaultProbeIT.AcknowledgedProbeController.class})
class AuthzUnannotatedShippedDefaultProbeIT extends BaseIntegrationTest {

    @Autowired WebApplicationContext web;
    @Autowired JwtAuthenticationFilter filter;
    @Autowired JwtUtil jwt;
    @Autowired UserDetailsService users;
    @Autowired SessionManagementService sessions;

    @RestController
    static class UnannotatedProbeController {
        @GetMapping("/api/integration-test/authz-probe/shipped-default-unannotated")
        public String unannotated() {
            return "unannotated";
        }
    }

    @RestController
    @AuthenticatedAccess("probe handler: explicitly acknowledged authenticated-only surface")
    static class AcknowledgedProbeController {
        @GetMapping("/api/integration-test/authz-probe/shipped-default-acknowledged")
        public String acknowledged() {
            return "acknowledged";
        }
    }

    @Test
    void shippedDefaultDeniesUnannotatedHandlerAndAllowsAcknowledgedAuthenticatedAccess() throws Exception {
        MockMvc mvc = webAppContextSetup(web).addFilters(filter).build();
        String token = jwt.generateTokenWithTenantId(users.loadUserByUsername(testUser.getPid()),
                testUser.getPid(), testTenant.getId(), testTenantMember.getId(),
                testUser.getSecurityVersion() == null ? 0 : testUser.getSecurityVersion());
        sessions.createSession(testUser.getId(), token, "127.0.0.1", "authz-shipped-default-probe-it");
        try {
            mvc.perform(get("/api/integration-test/authz-probe/shipped-default-unannotated")
                            .header("Authorization", "Bearer " + token)
                            .accept("application/json"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/integration-test/authz-probe/shipped-default-acknowledged")
                            .header("Authorization", "Bearer " + token)
                            .accept("application/json"))
                    .andExpect(status().isOk());
        } finally {
            SecurityContextHolder.clearContext();
            MetaContext.clear();
        }
    }
}
