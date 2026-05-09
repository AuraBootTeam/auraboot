package com.auraboot.framework.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DemoModeGuardTest {

    @Mock FilterChain chain;

    private DemoModeProperties props;
    private DemoModeGuard guard;

    @BeforeEach
    void setUp() {
        props = new DemoModeProperties();
        guard = new DemoModeGuard(props, new ObjectMapper());
    }

    @Test
    void disabled_passesThrough() throws Exception {
        props.setEnabled(false);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/license/issue");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        guard.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        assertEquals(200, resp.getStatus());
    }

    @Test
    void enabled_blocksLicenseIssue() throws Exception {
        props.setEnabled(true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/license/issue");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        guard.doFilter(req, resp, chain);

        verify(chain, never()).doFilter(req, resp);
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentType() != null && resp.getContentType().startsWith("application/json"));
        assertEquals("true", resp.getHeader("X-Auraboot-Demo"));
        assertTrue(resp.getContentAsString().contains("demo_mode_forbidden"));
    }

    @Test
    void enabled_blocksPluginPackageUpload() throws Exception {
        // The real plugin package upload endpoint is at /api/plugins/packages/upload
        // (PluginPackageController @RequestMapping "/api/plugins/packages").
        // The whole packages/** tree is covered by the default deny list.
        props.setEnabled(true);
        for (String path : List.of(
                "/api/plugins/packages/upload",
                "/api/plugins/packages/some-id/activate",
                "/api/plugins/some-id/install")) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            guard.doFilter(req, resp, chain);
            assertEquals(403, resp.getStatus(), "expected 403 for " + path);
            verify(chain, never()).doFilter(req, resp);
        }
    }

    @Test
    void enabled_blocksAdminWildcard() throws Exception {
        props.setEnabled(true);
        for (String path : List.of("/api/admin/users", "/api/admin/tenants/x/destroy", "/api/admin/anything/here")) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            guard.doFilter(req, resp, chain);
            assertEquals(403, resp.getStatus(), "expected 403 for " + path);
            verify(chain, never()).doFilter(req, resp);
        }
    }

    @Test
    void enabled_allowsNormalCrud() throws Exception {
        props.setEnabled(true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/crm-lead/list");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        guard.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        assertEquals(200, resp.getStatus());
    }

    @Test
    void enabled_allowsAuthLogin() throws Exception {
        props.setEnabled(true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        guard.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
    }

    @Test
    void enabled_allowlistOverridesDenylist() throws Exception {
        props.setEnabled(true);
        // Custom: open up admin/system-info (e.g., for the demo banner config)
        props.setAllowPaths(List.of("/api/admin/system-info"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/admin/system-info");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        guard.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
    }

    @Test
    void enabled_blocksPasswordResetForOtherUsers() throws Exception {
        props.setEnabled(true);
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/users/abc-123/password");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        guard.doFilter(req, resp, chain);

        assertEquals(403, resp.getStatus());
    }

    @Test
    void enabled_customDenyPathsRespected() throws Exception {
        props.setEnabled(true);
        props.setDenyPaths(List.of("/api/super-secret/**"));

        // Was previously blocked under defaults (license), now overridden to allow:
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/license/issue");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        guard.doFilter(req1, resp1, chain);
        verify(chain, times(1)).doFilter(req1, resp1);

        // New custom deny pattern:
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/super-secret/key");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        guard.doFilter(req2, resp2, chain);
        assertEquals(403, resp2.getStatus());
    }

}
