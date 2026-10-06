package com.auraboot.framework.branding;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.permission.interceptor.PermissionInterceptor;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual appearance handlers checked by the production permission interceptor. */
class AuthAppearancePermissionTest {
    private final AdminRoleChecker roles = mock(AdminRoleChecker.class);
    private final PermissionInterceptor interceptor = new PermissionInterceptor(
            mock(UserPermissionService.class), mock(MenuMapper.class), roles, mock(PageSchemaMapper.class));
    private final AuthAppearanceController controller = new AuthAppearanceController(
            mock(AuthAppearanceService.class), mock(AuthAppearanceAssetService.class));

    @AfterEach void clear() { MetaContext.clear(); SecurityContextHolder.clearContext(); }

    private List<HandlerMethod> handlers() {
        Set<String> names = Set.of("view", "save", "publish", "rollback", "upload");
        var handlers = java.util.Arrays.stream(AuthAppearanceController.class.getDeclaredMethods())
                .filter(method -> names.contains(method.getName()))
                .map(method -> new HandlerMethod(controller, method)).toList();
        assertThat(handlers).hasSize(5);
        return handlers;
    }
    private void authenticate(Set<Long> claimedRoles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("appearance-admin", null, List.of()));
        MetaContext.setContext(9L, 7L, "test-actor", "appearance-admin", claimedRoles);
    }
    private boolean check(HandlerMethod handler) throws Exception {
        return interceptor.preHandle(new MockHttpServletRequest("POST", "/api/admin/auth-appearance"),
                new MockHttpServletResponse(), handler);
    }

    @Test void anonymousCannotManageAppearanceInAnyAuthorizationMode() {
        for (String mode : List.of("allow", "shadow", "deny")) {
            org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "unannotatedMode", mode);
            for (var handler : handlers()) {
                assertThatThrownBy(() -> check(handler)).isInstanceOf(AccessDeniedException.class);
            }
        }
        verifyNoInteractions(roles);
    }
    @Test void tenantAdminAndForgedContextRolesDoNotGrantDeploymentAdministration() {
        authenticate(Set.of(1L, 2L));
        when(roles.hasRole(9L, 7L, RoleCodes.PLATFORM_ADMIN)).thenReturn(false);
        for (String mode : List.of("allow", "shadow", "deny")) {
            org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "unannotatedMode", mode);
            for (var handler : handlers()) {
                assertThatThrownBy(() -> check(handler)).isInstanceOf(AccessDeniedException.class);
            }
        }
    }
    @Test void verifiedPlatformAdministratorCanReachAllManagementHandlersInDenyMode() throws Exception {
        authenticate(Set.of());
        org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "unannotatedMode", "deny");
        when(roles.hasRole(9L, 7L, RoleCodes.PLATFORM_ADMIN)).thenReturn(true);
        for (var handler : handlers()) assertThat(check(handler)).isTrue();
        verify(roles, times(5)).hasRole(9L, 7L, RoleCodes.PLATFORM_ADMIN);
    }
    @Test void authenticatedTokenWithoutActorContextCannotManageAppearance() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("appearance-admin", null, List.of()));
        for (var handler : handlers()) {
            assertThatThrownBy(() -> check(handler)).isInstanceOf(AccessDeniedException.class);
        }
    }
}
