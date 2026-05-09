package com.auraboot.framework.application.web.filter;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JwtAuthenticationFilter}.  Covers the major branches:
 * missing header → 401, expired token → 401, generic JWT failure → 401,
 * happy-path token sets MetaContext + chain proceeds, security version mismatch,
 * session-invalid + session-check throws.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtAuthenticationFilterTest {

    @Mock JwtUtil jwtUtil;
    @Mock UserDetailsService userDetailsService;
    @Mock UserService userService;
    @Mock SessionManagementService sessionManagementService;
    @Mock SystemModeService systemModeService;
    @Mock TenantMemberService tenantMemberService;
    @Mock UserRoleService userRoleService;
    @Mock FilterChain chain;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtUtil, userDetailsService);
        ReflectionTestUtils.setField(filter, "userService", userService);
        ReflectionTestUtils.setField(filter, "sessionManagementService", sessionManagementService);
        ReflectionTestUtils.setField(filter, "systemModeService", systemModeService);
        ReflectionTestUtils.setField(filter, "tenantMemberService", tenantMemberService);
        ReflectionTestUtils.setField(filter, "userRoleService", userRoleService);
        ReflectionTestUtils.setField(filter, "activeProfile", "");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MetaContext.clear();
    }

    private MockHttpServletRequest req() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/protected");
        r.setServletPath("/api/protected");
        return r;
    }

    @Test
    void missingAuthorizationHeader_returns401AndDoesNotChain() throws Exception {
        MockHttpServletRequest req = req();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void expiredJwt_returns401() throws Exception {
        MockHttpServletRequest req = req();
        req.addHeader("Authorization", "Bearer some.jwt.token");
        when(jwtUtil.extractIdentifier("some.jwt.token"))
                .thenThrow(new ExpiredJwtException(null, null, "expired"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void invalidJwt_returns401() throws Exception {
        MockHttpServletRequest req = req();
        req.addHeader("Authorization", "Bearer broken");
        when(jwtUtil.extractIdentifier("broken")).thenThrow(new RuntimeException("bad sig"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void validToken_setsAuthentication_andChainsForward() throws Exception {
        MockHttpServletRequest req = req();
        req.addHeader("Authorization", "Bearer valid.token");

        CustomUserDetails ud = new CustomUserDetails("alice", "p", 7L, "alice_pid",
                Collections.emptyList(), true, true, true, true);
        when(jwtUtil.extractIdentifier("valid.token")).thenReturn("alice_pid");
        when(userDetailsService.loadUserByUsername("alice_pid")).thenReturn(ud);
        when(jwtUtil.validateToken(eq("valid.token"), eq(ud))).thenReturn(true);
        when(jwtUtil.extractSecurityVersion("valid.token")).thenReturn(0);
        User user = new User();
        user.setSecurityVersion(0);
        when(userService.findByPid("alice_pid")).thenReturn(user);
        when(sessionManagementService.isSessionValid("valid.token")).thenReturn(true);
        when(jwtUtil.extractTenantId("valid.token")).thenReturn(100L);
        when(jwtUtil.extractMemberId("valid.token")).thenReturn(55L);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(55L, 100L))
                .thenReturn(List.of(1L, 2L));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(sessionManagementService).updateLastActive("valid.token");
        // After chain completes the filter clears MetaContext in finally.
        assertFalse(MetaContext.exists());
    }

    @Test
    void securityVersionMismatch_rejects() throws Exception {
        MockHttpServletRequest req = req();
        req.addHeader("Authorization", "Bearer v.token");

        CustomUserDetails ud = new CustomUserDetails("bob", "p", 1L, "bob_pid",
                Collections.emptyList(), true, true, true, true);
        when(jwtUtil.extractIdentifier("v.token")).thenReturn("bob_pid");
        when(userDetailsService.loadUserByUsername("bob_pid")).thenReturn(ud);
        when(jwtUtil.validateToken(anyString(), eq(ud))).thenReturn(true);
        when(jwtUtil.extractSecurityVersion("v.token")).thenReturn(1);
        User user = new User();
        user.setSecurityVersion(2); // db ahead of token
        when(userService.findByPid("bob_pid")).thenReturn(user);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void sessionInvalid_rejects() throws Exception {
        MockHttpServletRequest req = req();
        req.addHeader("Authorization", "Bearer s.token");

        CustomUserDetails ud = new CustomUserDetails("c", "p", 1L, "c_pid",
                Collections.emptyList(), true, true, true, true);
        when(jwtUtil.extractIdentifier("s.token")).thenReturn("c_pid");
        when(userDetailsService.loadUserByUsername("c_pid")).thenReturn(ud);
        when(jwtUtil.validateToken(anyString(), eq(ud))).thenReturn(true);
        when(jwtUtil.extractSecurityVersion("s.token")).thenReturn(0);
        User user = new User();
        user.setSecurityVersion(0);
        when(userService.findByPid("c_pid")).thenReturn(user);
        when(sessionManagementService.isSessionValid("s.token")).thenReturn(false);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void sessionCheckThrows_failsClosed() throws Exception {
        MockHttpServletRequest req = req();
        req.addHeader("Authorization", "Bearer s.token");

        CustomUserDetails ud = new CustomUserDetails("c", "p", 1L, "c_pid",
                Collections.emptyList(), true, true, true, true);
        when(jwtUtil.extractIdentifier("s.token")).thenReturn("c_pid");
        when(userDetailsService.loadUserByUsername("c_pid")).thenReturn(ud);
        when(jwtUtil.validateToken(anyString(), eq(ud))).thenReturn(true);
        when(jwtUtil.extractSecurityVersion("s.token")).thenReturn(0);
        User user = new User();
        user.setSecurityVersion(0);
        when(userService.findByPid("c_pid")).thenReturn(user);
        when(sessionManagementService.isSessionValid("s.token"))
                .thenThrow(new RuntimeException("redis down"));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void shouldNotFilter_optionsMethodSkips() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("options", "/api/foo");
        req.setServletPath("/api/foo");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter", req));
    }

    @Test
    void rbacLookupFailure_doesNotPropagate() throws Exception {
        MockHttpServletRequest req = req();
        req.addHeader("Authorization", "Bearer t.token");
        CustomUserDetails ud = new CustomUserDetails("x", "p", 1L, "x_pid",
                Collections.emptyList(), true, true, true, true);
        when(jwtUtil.extractIdentifier("t.token")).thenReturn("x_pid");
        when(userDetailsService.loadUserByUsername("x_pid")).thenReturn(ud);
        when(jwtUtil.validateToken(anyString(), eq(ud))).thenReturn(true);
        when(jwtUtil.extractSecurityVersion("t.token")).thenReturn(0);
        User user = new User();
        user.setSecurityVersion(0);
        when(userService.findByPid("x_pid")).thenReturn(user);
        when(sessionManagementService.isSessionValid("t.token")).thenReturn(true);
        when(jwtUtil.extractTenantId("t.token")).thenReturn(10L);
        when(jwtUtil.extractMemberId("t.token")).thenReturn(99L);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(99L, 10L))
                .thenThrow(new RuntimeException("rbac db hiccup"));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);

        // Request still proceeds; rbac failure is logged and swallowed by design.
        verify(chain).doFilter(req, resp);
    }
}
