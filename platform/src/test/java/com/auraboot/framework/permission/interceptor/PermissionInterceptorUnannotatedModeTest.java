package com.auraboot.framework.permission.interceptor;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.service.UserPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Staged default-deny migration mechanism (deep review §default-deny). Verifies the
 * {@code aura.security.authz.unannotated-mode} behavior for handlers with no @RequirePermission:
 * allow (legacy) / shadow (allow + log, default) / deny (fail-closed), plus the
 * {@link AuthenticatedAccess} exemption.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionInterceptorUnannotatedModeTest {

    @Mock private UserPermissionService userPermissionService;
    @Mock private MenuMapper menuMapper;
    @Mock private AdminRoleChecker adminRoleChecker;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new PermissionInterceptor(userPermissionService, menuMapper, adminRoleChecker);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MetaContext.clear();
    }

    static class Handlers {
        public void unannotated() {}

        @AuthenticatedAccess("operates only on the caller's own data")
        public void authenticatedOnly() {}
    }

    private HandlerMethod hm(String name) throws NoSuchMethodException {
        Method m = Handlers.class.getMethod(name);
        return new HandlerMethod(new Handlers(), m);
    }

    @Test
    @DisplayName("default mode (shadow): un-annotated handler is allowed without touching permission service")
    void shadowAllows() throws Exception {
        HandlerMethod handler = hm("unannotated");
        boolean ok = interceptor.preHandle(request, response, handler);
        assertThat(ok).isTrue();
        verifyNoInteractions(userPermissionService);
    }

    @Test
    @DisplayName("allow mode: un-annotated handler is allowed (legacy fail-open)")
    void allowAllows() throws Exception {
        interceptor.setUnannotatedMode("allow");
        assertThat(interceptor.preHandle(request, response, hm("unannotated"))).isTrue();
    }

    @Test
    @DisplayName("deny mode: un-annotated handler is rejected (default-deny)")
    void denyRejects() throws Exception {
        interceptor.setUnannotatedMode("deny");
        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm("unannotated")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("deny mode: @AuthenticatedAccess handler is still allowed")
    void denyAllowsAuthenticatedAccess() throws Exception {
        interceptor.setUnannotatedMode("deny");
        assertThat(interceptor.preHandle(request, response, hm("authenticatedOnly"))).isTrue();
    }
}
