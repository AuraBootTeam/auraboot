package com.auraboot.framework.application.web.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SecurityHeadersFilterTest {

    @Mock FilterChain chain;

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void doFilter_nonApiPath_setsBaseHeadersOnly() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/static/foo.js");
        req.setServletPath("/static/foo.js");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals("nosniff", resp.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", resp.getHeader("X-Frame-Options"));
        assertEquals("1; mode=block", resp.getHeader("X-XSS-Protection"));
        assertEquals("max-age=31536000; includeSubDomains", resp.getHeader("Strict-Transport-Security"));
        assertEquals("strict-origin-when-cross-origin", resp.getHeader("Referrer-Policy"));
        assertEquals("camera=(), microphone=(), geolocation=()", resp.getHeader("Permissions-Policy"));
        assertNull(resp.getHeader("Content-Security-Policy"));
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_apiPath_addsCspHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/foo");
        req.setServletPath("/api/foo");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals("default-src 'none'; frame-ancestors 'none'",
                resp.getHeader("Content-Security-Policy"));
        verify(chain).doFilter(req, resp);
    }
}
