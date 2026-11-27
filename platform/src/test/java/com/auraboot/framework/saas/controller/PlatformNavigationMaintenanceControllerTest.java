package com.auraboot.framework.saas.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.saas.bootstrap.PlatformConsoleMenuBootstrap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlatformNavigationMaintenanceControllerTest {
    private final PlatformConsoleMenuBootstrap menus = mock(PlatformConsoleMenuBootstrap.class);
    private final AdminRoleChecker roles = mock(AdminRoleChecker.class);
    private final AdminEventLogService audit = mock(AdminEventLogService.class);
    private final PlatformNavigationMaintenanceController controller = new PlatformNavigationMaintenanceController(menus, roles, audit);

    @Test void explicitPlatformAdministratorCanReconcileAndProducesAudit() {
        try (var context = mockStatic(MetaContext.class)) {
            context.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            context.when(MetaContext::getCurrentUserId).thenReturn(42L);
            context.when(MetaContext::getCurrentExecutionScope).thenReturn("platform");
            when(roles.hasRole(1L, 42L, RoleCodes.PLATFORM_ADMIN)).thenReturn(true);
            assertThat(controller.reconcile().getData()).containsEntry("reconciled", true);
            verify(menus).reconcile(1L, 42L);
            verify(audit).record(argThat(event -> "platform.navigation.reconcile".equals(event.getActionType()) && event.getSuccess()));
        }
    }

    @Test void businessTenantCannotReconcileEvenWithAnAdminRole() {
        try (var context = mockStatic(MetaContext.class)) {
            context.when(MetaContext::getCurrentTenantId).thenReturn(9L);
            context.when(MetaContext::getCurrentUserId).thenReturn(42L);
            assertThat(controller.reconcile().getCode()).isEqualTo("403");
            verifyNoInteractions(menus, roles, audit);
        }
    }

    @Test void systemMemberWithoutPlatformRoleCannotReconcile() {
        try (var context = mockStatic(MetaContext.class)) {
            context.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            context.when(MetaContext::getCurrentUserId).thenReturn(42L);
            context.when(MetaContext::getCurrentExecutionScope).thenReturn("platform");
            assertThat(controller.reconcile().getCode()).isEqualTo("403");
            verifyNoInteractions(menus, audit);
        }
    }

    @Test void impersonationCannotReconcileEvenForPlatformAdministrator() {
        try (var context = mockStatic(MetaContext.class)) {
            context.when(MetaContext::getCurrentTenantId).thenReturn(1L);
            context.when(MetaContext::getCurrentUserId).thenReturn(42L);
            context.when(MetaContext::isImpersonating).thenReturn(true);
            assertThat(controller.reconcile().getCode()).isEqualTo("403");
            verifyNoInteractions(menus, roles, audit);
        }
    }
}
