package com.auraboot.framework.application.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ExternalMachineAuthenticationFilterTest {

    @AfterEach
    void clearContext() {
        MetaContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void supportedRequestEstablishesTenantAuthenticationAndAlwaysClearsIt() throws Exception {
        AtomicBoolean audited = new AtomicBoolean();
        ExternalMachineAuthenticator authenticator = new ExternalMachineAuthenticator() {
            public boolean supports(jakarta.servlet.http.HttpServletRequest request) { return true; }
            public MachinePrincipal authenticate(jakarta.servlet.http.HttpServletRequest request) {
                return new MachinePrincipal(42L, "token-pid", "ERP application", Set.of("openapi.profile.read"));
            }
            public void recordCall(MachinePrincipal principal,
                                   jakarta.servlet.http.HttpServletRequest request,
                                   int status, long durationMillis) {
                audited.set(status == 200 && principal.tenantId() == 42L);
            }
        };
        var filter = new ExternalMachineAuthenticationFilter(List.of(authenticator), new ObjectMapper());
        var request = new MockHttpServletRequest("GET", "/api/open/v1/whoami");
        request.setServletPath("/api/open/v1/whoami");
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            assertEquals(42L, MetaContext.getCurrentTenantId());
            assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
            assertTrue(Boolean.TRUE.equals(request.getAttribute(
                    ExternalMachineAuthenticationFilter.AUTHENTICATED_ATTRIBUTE)));
        };

        filter.doFilter(request, response, chain);

        assertTrue(audited.get());
        assertFalse(MetaContext.exists());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void policyRejectionDoesNotReachController() throws Exception {
        ExternalMachineAuthenticator authenticator = new ExternalMachineAuthenticator() {
            public boolean supports(jakarta.servlet.http.HttpServletRequest request) { return true; }
            public MachinePrincipal authenticate(jakarta.servlet.http.HttpServletRequest request) {
                throw new ExternalMachineAuthException(429, "rate_limit_exceeded");
            }
        };
        var filter = new ExternalMachineAuthenticationFilter(List.of(authenticator), new ObjectMapper());
        var request = new MockHttpServletRequest("GET", "/api/open/v1/whoami");
        request.setServletPath("/api/open/v1/whoami");
        var response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("rate_limit_exceeded"));
        assertFalse(chained.get());
    }
}
