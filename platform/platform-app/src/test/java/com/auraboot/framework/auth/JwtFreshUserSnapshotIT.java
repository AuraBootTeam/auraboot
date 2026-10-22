package com.auraboot.framework.auth;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.web.filter.JwtAuthenticationFilter;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.service.UnifiedUserDetailsService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL, signed JWT, real filter and session service; no Redis or auth mocks. */
class JwtFreshUserSnapshotIT extends BaseIntegrationTest {
    @Autowired UserService users;
    @Autowired UnifiedUserDetailsService details;
    @Autowired JwtUtil jwt;
    @Autowired SessionManagementService sessions;
    @Autowired JwtAuthenticationFilter filter;

    @Test
    void oldTokenObservesDisableSecurityVersionAndSessionRevocation() throws Exception {
        User user = users.signUp("fresh-snapshot-" + System.nanoTime() + "@example.com", "SnapshotPass123!");
        String token = jwt.generateTokenWithTenantId(details.loadUserByPid(user.getPid()),
                user.getPid(), getTestTenant().getId(), 0);
        sessions.createSession(user.getId(), token, "127.0.0.1", "performance-regression");
        assertRequest(token, true);

        user.setEnabled(false);
        users.update(user);
        assertRequest(token, false);
        user.setEnabled(true);
        users.update(user);
        assertRequest(token, true);

        user.setSecurityVersion(1);
        users.update(user);
        assertRequest(token, false);
        String renewed = jwt.generateTokenWithTenantId(details.loadUserByPid(user.getPid()),
                user.getPid(), getTestTenant().getId(), 1);
        sessions.createSession(user.getId(), renewed, "127.0.0.1", "performance-regression");
        assertRequest(renewed, true);
        sessions.revokeSessionByToken(renewed);
        assertRequest(renewed, false);
    }

    private void assertRequest(String token, boolean allowed) throws Exception {
        SecurityContextHolder.clearContext();
        MetaContext.clear();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/i18n/zh-CN");
        request.setServletPath("/api/i18n/zh-CN");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        try {
            filter.doFilter(request, response, (req, res) -> {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
                reached.set(true);
            });
            assertThat(reached.get()).isEqualTo(allowed);
            assertThat(response.getStatus()).isEqualTo(allowed ? 200 : 401);
        } finally {
            SecurityContextHolder.clearContext();
            MetaContext.clear();
        }
    }
}
