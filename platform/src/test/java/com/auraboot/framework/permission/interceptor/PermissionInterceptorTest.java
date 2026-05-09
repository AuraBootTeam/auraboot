package com.auraboot.framework.permission.interceptor;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionInterceptorTest {

    @Mock
    private UserPermissionService userPermissionService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new PermissionInterceptor(userPermissionService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- handler classes for HandlerMethod construction ----
    static class StaticHandler {
        @RequirePermission("model.user.read")
        public void readUser() {}

        @RequirePermission(value = "dynamic.{pageKey}.read")
        public void dynamicByPageKey() {}

        @RequirePermission(value = "model.{pageKey}.write")
        public void dynamicWithRawFallback() {}

        @RequirePermission(value = "x.y.z", optional = true)
        public void optionalCheck() {}

        @RequirePermission(value = "needs.{missingVar}.x")
        public void unresolvedPlaceholder() {}

        public void noAnnotation() {}
    }

    @RequirePermission("class.level.read")
    static class ClassLevelHandler {
        public void anyMethod() {}
    }

    private HandlerMethod handlerMethod(Class<?> clazz, String name) throws NoSuchMethodException {
        Method method = clazz.getMethod(name);
        return new HandlerMethod(clazz.getDeclaredConstructors()[0].getDeclaringClass()
                .cast(newInstance(clazz)), method);
    }

    private static Object newInstance(Class<?> c) {
        try {
            return c.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void authenticate(Long userId) {
        CustomUserDetails details = new CustomUserDetails(
                "u", "p", userId, "pid", null, true, true, true, true);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(details, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void preHandle_nonHandlerMethod_allowsAccess() throws Exception {
        boolean ok = interceptor.preHandle(request, response, new Object());
        assertThat(ok).isTrue();
    }

    @Test
    void preHandle_noAnnotation_allowsAccess() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "noAnnotation");
        boolean ok = interceptor.preHandle(request, response, hm);
        assertThat(ok).isTrue();
        verifyNoInteractions(userPermissionService);
    }

    @Test
    void preHandle_noAuth_throwsAuthenticationException() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "readUser");
        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void preHandle_authenticatedHasPermission_allows() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "readUser");
        authenticate(7L);
        when(userPermissionService.hasPermission(7L, "model.user.read")).thenReturn(true);

        boolean ok = interceptor.preHandle(request, response, hm);
        assertThat(ok).isTrue();
    }

    @Test
    void preHandle_lacksPermission_throwsAccessDenied() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "readUser");
        authenticate(7L);
        when(userPermissionService.hasPermission(7L, "model.user.read")).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preHandle_optionalAndDenied_stillAllows() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "optionalCheck");
        authenticate(7L);
        when(userPermissionService.hasPermission(7L, "x.y.z")).thenReturn(false);

        boolean ok = interceptor.preHandle(request, response, hm);
        assertThat(ok).isTrue();
    }

    @Test
    void preHandle_dynamicPageKey_resolvedAndChecked() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "dynamicByPageKey");
        authenticate(7L);
        Map<String, String> pathVars = new HashMap<>();
        pathVars.put("pageKey", "user-table");
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).thenReturn(pathVars);
        // Stub primary lookup as true (any converted form).
        when(userPermissionService.hasPermission(eq(7L), anyString())).thenReturn(true);

        boolean ok = interceptor.preHandle(request, response, hm);
        assertThat(ok).isTrue();
    }

    @Test
    void preHandle_dynamicPageKey_rawFallback() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "dynamicWithRawFallback");
        authenticate(7L);
        Map<String, String> pathVars = new HashMap<>();
        // sl_price_list — converter strips _list, raw keeps it
        pathVars.put("pageKey", "sl_price_list");
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).thenReturn(pathVars);
        // Primary check fails, raw fallback succeeds
        when(userPermissionService.hasPermission(eq(7L), anyString()))
                .thenReturn(false)
                .thenReturn(true);

        boolean ok = interceptor.preHandle(request, response, hm);
        assertThat(ok).isTrue();
    }

    @Test
    void preHandle_unresolvedPlaceholder_throwsAccessDenied() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "unresolvedPlaceholder");
        authenticate(7L);
        Map<String, String> pathVars = new HashMap<>();
        pathVars.put("other", "v");
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).thenReturn(pathVars);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preHandle_missingPathVariablesForTemplate_throwsAccessDenied() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "dynamicByPageKey");
        authenticate(7L);
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).thenReturn(null);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preHandle_invalidPrincipalType_throwsAuthException() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "readUser");
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("not-custom-details", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void preHandle_userIdNullInPrincipal_throwsAuthException() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "readUser");
        CustomUserDetails details = new CustomUserDetails(
                "u", "p", null, "pid", null, true, true, true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, List.of()));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void preHandle_classLevelAnnotation_used() throws Exception {
        HandlerMethod hm = handlerMethod(ClassLevelHandler.class, "anyMethod");
        authenticate(7L);
        when(userPermissionService.hasPermission(7L, "class.level.read")).thenReturn(true);

        boolean ok = interceptor.preHandle(request, response, hm);
        assertThat(ok).isTrue();
    }
}
