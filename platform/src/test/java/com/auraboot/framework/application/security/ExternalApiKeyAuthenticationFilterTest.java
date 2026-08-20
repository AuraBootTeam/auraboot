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

class ExternalApiKeyAuthenticationFilterTest {

    @AfterEach
    void clearContext() {
        MetaContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void supportedRequestEstablishesTenantAuthenticationAndAlwaysClearsIt() throws Exception {
        AtomicBoolean audited = new AtomicBoolean();
        ExternalApiKeyAuthenticator authenticator = new ExternalApiKeyAuthenticator() {
            public boolean supports(jakarta.servlet.http.HttpServletRequest request) { return true; }
            public ExternalApiKeyPrincipal authenticate(jakarta.servlet.http.HttpServletRequest request) {
                return new ExternalApiKeyPrincipal(42L, "key-pid", "ERP", Set.of("qr:read"));
            }
            public void recordCall(ExternalApiKeyPrincipal principal,
                                   jakarta.servlet.http.HttpServletRequest request,
                                   int status, long durationMillis) {
                audited.set(status == 200 && principal.tenantId() == 42L);
            }
        };
        var filter = new ExternalApiKeyAuthenticationFilter(List.of(authenticator), new ObjectMapper());
        var request = new MockHttpServletRequest("GET", "/api/open/qr/v1/codes");
        request.setServletPath("/api/open/qr/v1/codes");
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            assertEquals(42L, MetaContext.getCurrentTenantId());
            assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
            assertTrue(Boolean.TRUE.equals(request.getAttribute(
                    ExternalApiKeyAuthenticationFilter.AUTHENTICATED_ATTRIBUTE)));
        };

        filter.doFilter(request, response, chain);

        assertTrue(audited.get());
        assertFalse(MetaContext.exists());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void policyRejectionDoesNotReachController() throws Exception {
        ExternalApiKeyAuthenticator authenticator = new ExternalApiKeyAuthenticator() {
            public boolean supports(jakarta.servlet.http.HttpServletRequest request) { return true; }
            public ExternalApiKeyPrincipal authenticate(jakarta.servlet.http.HttpServletRequest request) {
                throw new ExternalApiKeyException(429, "api_rate_limited");
            }
        };
        var filter = new ExternalApiKeyAuthenticationFilter(List.of(authenticator), new ObjectMapper());
        var request = new MockHttpServletRequest("GET", "/api/open/qr/v1/codes");
        request.setServletPath("/api/open/qr/v1/codes");
        var response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chained.set(true));

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("api_rate_limited"));
        assertFalse(chained.get());
    }
}
