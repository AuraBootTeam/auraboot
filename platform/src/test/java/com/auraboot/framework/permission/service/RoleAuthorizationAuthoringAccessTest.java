package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleAuthorizationAuthoringAccessTest {
    private final AdminRoleChecker administrators = mock(AdminRoleChecker.class);
    private final UserPermissionService permissions = mock(UserPermissionService.class);
    private final RoleMapper roles = mock(RoleMapper.class);
    private final RoleAuthorizationAuthoringAccess access = new RoleAuthorizationAuthoringAccess(administrators, permissions, roles);

    @BeforeEach void context() {
        MetaContext.setContext(7L, 20L, "user", "test");
        MetaContext.setMemberId(30L);
    }
    @AfterEach void cleanup() { MetaContext.clear(); }

    @Test void validAdministratorAndPermissionAreBothRequired() {
        when(administrators.hasRole(7L, 20L, RoleCodes.TENANT_ADMIN)).thenReturn(true);
        when(permissions.hasPermission(20L, MetaPermission.ROLE_MANAGE)).thenReturn(true);
        assertThatCode(access::requireWrite).doesNotThrowAnyException();
        verify(administrators).hasRole(7L, 20L, RoleCodes.TENANT_ADMIN);
        verify(permissions).hasPermission(20L, MetaPermission.ROLE_MANAGE);
    }
    @Test void delegatedPermissionAloneCannotGrantDuties() {
        assertThatThrownBy(access::requireWrite).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(permissions, roles);
    }
    @Test void AdministratorWithoutRoleAuthoringPermissionCannotWrite() {
        when(administrators.hasRole(7L, 20L, RoleCodes.TENANT_ADMIN)).thenReturn(true);
        assertThatThrownBy(access::requireWrite).isInstanceOf(AccessDeniedException.class);
    }
    @Test void missingContextCannotBecomeAServiceBypass() {
        MetaContext.clear();
        assertThatThrownBy(access::requireWrite).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(administrators, permissions, roles);
    }
    @Test void missingMemberCannotBecomeAServiceBypass() {
        MetaContext.setMemberId(null);
        assertThatThrownBy(access::requireWrite).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(administrators, permissions, roles);
    }
    @Test void numericRoleIdentityRetainsTenantOwnership() {
        Role role = new Role(); role.setId(1L); role.setTenantId(7L);
        when(roles.selectById(1L)).thenReturn(role);
        assertThatCode(() -> access.requireTarget(1L)).doesNotThrowAnyException();
        role.setDeletedFlag(true);
        assertThatThrownBy(() -> access.requireTarget(1L)).isInstanceOf(AccessDeniedException.class);
        role.setDeletedFlag(false);
        role.setTenantId(8L);
        assertThatThrownBy(() -> access.requireTarget(1L)).isInstanceOf(AccessDeniedException.class);
        role.setTenantId(null);
        assertThatThrownBy(() -> access.requireTarget(1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.requireTarget(2L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.requireTarget(null)).isInstanceOf(AccessDeniedException.class);
    }
}
