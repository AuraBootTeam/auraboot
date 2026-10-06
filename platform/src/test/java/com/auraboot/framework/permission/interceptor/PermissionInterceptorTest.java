package com.auraboot.framework.permission.interceptor;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.annotation.DisallowImpersonation;
import com.auraboot.framework.permission.constants.MetaPermission;
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
    private MenuMapper menuMapper;
    @Mock
    private AdminRoleChecker adminRoleChecker;
    @Mock
    private PageSchemaMapper pageSchemaMapper;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new PermissionInterceptor(userPermissionService, menuMapper, adminRoleChecker, pageSchemaMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MetaContext.clear();
    }

    @Test
    void dashboardVersionHandlersKeepReadWriteAndPublicSharingIndependent() throws Exception {
        authenticate(7L);
        java.util.Set<String> grants = new java.util.HashSet<>(java.util.Set.of(MetaPermission.DASHBOARD_READ));
        when(userPermissionService.hasPermission(eq(7L), anyString()))
                .thenAnswer(invocation -> grants.contains(invocation.getArgument(1, String.class)));
        var service = mock(com.auraboot.framework.versioning.service.VersionHistoryService.class);
        var controller = new com.auraboot.framework.versioning.controller.VersionHistoryController(service,
                mock(com.auraboot.framework.dashboard.service.DashboardService.class));
        var rollback = new HandlerMethod(controller, controller.getClass().getMethod("rollback", String.class, String.class));
        var sharing = new com.auraboot.framework.view.controller.ViewShareController(
                mock(com.auraboot.framework.view.service.ViewShareService.class));
        var share = new HandlerMethod(sharing, sharing.getClass().getMethod("shareView", String.class, Map.class));
        var revokeShare = new HandlerMethod(sharing, sharing.getClass().getMethod("revokeShare", String.class));
        var shareStatus = new HandlerMethod(sharing, sharing.getClass().getMethod("getShareStatus", String.class));
        var shareHandlers = List.of(share, revokeShare, shareStatus);
        for (String name : List.of("getHistory", "getVersion", "countVersions")) {
            Method method = name.equals("getVersion")
                    ? controller.getClass().getMethod(name, String.class, String.class)
                    : controller.getClass().getMethod(name, String.class);
            assertThat(interceptor.preHandle(request, response, new HandlerMethod(controller, method))).isTrue();
        }
        assertThatThrownBy(() -> interceptor.preHandle(request, response, rollback))
                .isInstanceOf(AccessDeniedException.class);
        grants.add(MetaPermission.DASHBOARD_MANAGE);
        assertThat(interceptor.preHandle(request, response, rollback)).isTrue();
        controller.rollback("test-dashboard", "version-1");
        verify(service).rollback("dashboard", "test-dashboard", "version-1");
        for (var handler : shareHandlers) {
            assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                    .isInstanceOf(AccessDeniedException.class);
        }
        grants.remove(MetaPermission.DASHBOARD_MANAGE);
        grants.add(MetaPermission.VIEW_PUBLIC_SHARE);
        for (var handler : shareHandlers) {
            assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        }
        grants.remove(MetaPermission.VIEW_PUBLIC_SHARE);
        for (var handler : shareHandlers) {
            assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> interceptor.preHandle(request, response, rollback))
                .isInstanceOf(AccessDeniedException.class);
        grants.clear();
        for (String name : List.of("getHistory", "getVersion", "countVersions")) {
            Method method = name.equals("getVersion")
                    ? controller.getClass().getMethod(name, String.class, String.class)
                    : controller.getClass().getMethod(name, String.class);
            assertThatThrownBy(() -> interceptor.preHandle(request, response, new HandlerMethod(controller, method)))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verify(service, times(1)).rollback(anyString(), anyString(), anyString());
    }

    // ---- handler classes for HandlerMethod construction ----
    static class StaticHandler {
        @RequirePermission("model.user.read")
        public void readUser() {}

        @RequirePermission(value = "dynamic.{pageKey}.read")
        public void dynamicByPageKey() {}

        @RequirePermission(value = "model.{pageKey}.write")
        public void dynamicWithRawFallback() {}

        @RequirePermission(value = "model.{pageKey}.read")
        public void dynamicModelRead() {}

        @RequirePermission(MetaPermission.PAGE_SCHEMA_READ)
        public void publishedPageSchemaRead() {}

        @RequirePermission(value = "x.y.z", optional = true)
        public void optionalCheck() {}

        @RequirePermission(value = "needs.{missingVar}.x")
        public void unresolvedPlaceholder() {}

        @RequirePermission(MetaPermission.DRT_DEFINITION_MANAGE)
        public void decisionOpsManage() {}

        @RequirePermission(MetaPermission.DRT_ROLLOUT_PROMOTE)
        public void decisionRolloutPromote() {}

        public void noAnnotation() {}

        @DisallowImpersonation
        public void securitySensitive() {}
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
    void preHandle_impersonationCannotReachSecuritySensitiveHandler() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "securitySensitive");
        MetaContext.setSessionContext(null, null, "tenant", null, null,
                "ready", 1, true, 77L, "web");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("unavailable while acting as another user");
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
    void preHandle_decisionOpsManagePermissionMissing_throwsAccessDenied() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "decisionOpsManage");
        authenticate(7L);
        when(userPermissionService.hasPermission(7L, MetaPermission.DRT_DEFINITION_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("permissionCode: " + MetaPermission.DRT_DEFINITION_MANAGE);
    }

    @Test
    void preHandle_decisionRolloutPromotePermissionMissing_throwsAccessDenied() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "decisionRolloutPromote");
        authenticate(7L);
        when(userPermissionService.hasPermission(7L, MetaPermission.DRT_ROLLOUT_PROMOTE)).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("permissionCode: " + MetaPermission.DRT_ROLLOUT_PROMOTE);
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
    void preHandle_dynamicPageRead_allowsViaMenuPermissionFallback() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "dynamicModelRead");
        authenticate(7L);
        MetaContext.setContext(100L, 7L, "u-pid", "tester");
        Map<String, String> pathVars = new HashMap<>();
        pathVars.put("pageKey", "decisionops_definitions_list");
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).thenReturn(pathVars);
        when(userPermissionService.hasPermission(7L, "model.decisionops_definitions.read")).thenReturn(false);
        when(userPermissionService.hasPermission(7L, "model.decisionops_definitions_list.read")).thenReturn(false);
        when(menuMapper.findPermissionCodeByPageKey(100L, "decisionops_definitions_list"))
                .thenReturn(MetaPermission.DRT_DEFINITION_READ);
        when(userPermissionService.hasPermission(7L, MetaPermission.DRT_DEFINITION_READ)).thenReturn(true);

        boolean ok = interceptor.preHandle(request, response, hm);

        assertThat(ok).isTrue();
        verify(menuMapper).findPermissionCodeByPageKey(100L, "decisionops_definitions_list");
    }

    @Test
    void preHandle_customFormPage_allowsViaDeclaredPageModelPermission() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "dynamicModelRead");
        authenticate(7L);
        MetaContext.setContext(100L, 7L, "u-pid", "tester");
        Map<String, String> pathVars = new HashMap<>();
        pathVars.put("pageKey", "crm_lead_move_to_pool_form");
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).thenReturn(pathVars);
        when(userPermissionService.hasPermission(7L, "model.crm_lead_move_to_pool.read")).thenReturn(false);
        when(userPermissionService.hasPermission(7L, "model.crm_lead_move_to_pool_form.read")).thenReturn(false);
        PageSchema page = new PageSchema();
        page.setModelCode("crm_lead_common");
        when(pageSchemaMapper.selectByPageKey("crm_lead_move_to_pool_form")).thenReturn(page);
        when(userPermissionService.hasPermission(7L, "model.crm_lead_common.read")).thenReturn(true);

        assertThat(interceptor.preHandle(request, response, hm)).isTrue();
        verify(pageSchemaMapper).selectByPageKey("crm_lead_move_to_pool_form");
    }

    @Test
    void preHandle_publishedPageSchemaRead_allowsViaMenuPermissionFallback() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "publishedPageSchemaRead");
        authenticate(7L);
        MetaContext.setContext(100L, 7L, "u-pid", "tester");
        Map<String, String> pathVars = new HashMap<>();
        pathVars.put("pageKey", "e2et_order_list");
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).thenReturn(pathVars);
        when(userPermissionService.hasPermission(7L, MetaPermission.PAGE_SCHEMA_READ)).thenReturn(false);
        when(menuMapper.findPermissionCodeByPageKey(100L, "e2et_order_list"))
                .thenReturn("e2et.order.read");
        when(userPermissionService.hasPermission(7L, "e2et.order.read")).thenReturn(true);

        boolean ok = interceptor.preHandle(request, response, hm);

        assertThat(ok).isTrue();
        verify(menuMapper).findPermissionCodeByPageKey(100L, "e2et_order_list");
    }

    @Test
    void preHandle_publishedPageSchemaRead_allowsViaModelReadFallback() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "publishedPageSchemaRead");
        authenticate(7L);
        MetaContext.setContext(100L, 7L, "u-pid", "tester");
        Map<String, String> pathVars = new HashMap<>();
        pathVars.put("pageKey", "e2et_order_list");
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).thenReturn(pathVars);
        when(userPermissionService.hasPermission(7L, MetaPermission.PAGE_SCHEMA_READ)).thenReturn(false);
        when(menuMapper.findPermissionCodeByPageKey(100L, "e2et_order_list")).thenReturn(null);
        when(userPermissionService.hasPermission(7L, "model.e2et_order.read")).thenReturn(true);

        boolean ok = interceptor.preHandle(request, response, hm);

        assertThat(ok).isTrue();
        verify(menuMapper).findPermissionCodeByPageKey(100L, "e2et_order_list");
    }

    @Test
    void preHandle_dynamicPageWrite_doesNotUseMenuReadFallback() throws Exception {
        HandlerMethod hm = handlerMethod(StaticHandler.class, "dynamicWithRawFallback");
        authenticate(7L);
        MetaContext.setContext(100L, 7L, "u-pid", "tester");
        Map<String, String> pathVars = new HashMap<>();
        pathVars.put("pageKey", "decisionops_definitions_list");
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).thenReturn(pathVars);
        when(userPermissionService.hasPermission(7L, "model.decisionops_definitions.write")).thenReturn(false);
        when(userPermissionService.hasPermission(7L, "model.decisionops_definitions_list.write")).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("permissionCode: model.decisionops_definitions.write");
        verifyNoInteractions(menuMapper);
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
