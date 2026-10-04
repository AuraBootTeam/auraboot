package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginImportRoleReconciliationTest {
    private final PluginImportServiceImpl service = mock(PluginImportServiceImpl.class, CALLS_REAL_METHODS);
    private final RoleService roles = mock(RoleService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final RolePermissionMapper bindings = mock(RolePermissionMapper.class);
    private final PluginResourceImporter importer = mock(PluginResourceImporter.class);
    private final PluginManifestExtended manifest = new PluginManifestExtended();
    private final RoleDefinitionDTO declaration = RoleDefinitionDTO.builder()
            .code("warehouse_operator").permissions(List.of("model.work_order.read")).build();

    PluginImportRoleReconciliationTest() {
        ReflectionTestUtils.setField(service, "roleService", roles);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        ReflectionTestUtils.setField(service, "rolePermissionMapper", bindings);
        ReflectionTestUtils.setField(service, "resourceImporter", importer);
        manifest.setRoles(List.of(declaration));
        Role role = new Role(); role.setId(3L); role.setCode(declaration.getCode());
        when(roles.findByTenantId(7L)).thenReturn(List.of(role));
    }

    private PermissionDTO permission() {
        PermissionDTO value = new PermissionDTO(); value.setId(9L); value.setCode("model.work_order.read");
        when(permissions.findByCode(value.getCode())).thenReturn(value);
        return value;
    }

    @Test void bindsLatePermissionWithoutCreatingRolesOrPermissions() {
        permission();
        when(bindings.countByRoleAndPermission(3L, 9L, 7L)).thenReturn(1);
        assertThat(service.reconcileDeclaredRolesStrictly(manifest, 7L)).isEqualTo(1);
        verify(importer).reconcileRolePermissions(declaration, 7L);
        verifyNoMoreInteractions(importer);
    }
    @Test void unresolvedPermissionFailsBeforeBinding() {
        assertThatThrownBy(() -> service.reconcileDeclaredRolesStrictly(manifest, 7L))
                .isInstanceOf(PluginException.class).hasMessageContaining("Unresolved declared permission");
        verifyNoInteractions(importer);
    }
    @Test void swallowedBindingFailureCannotProduceGreen() {
        permission();
        when(bindings.countByRoleAndPermission(3L, 9L, 7L)).thenReturn(0);
        assertThatThrownBy(() -> service.reconcileDeclaredRolesStrictly(manifest, 7L))
                .isInstanceOf(PluginException.class).hasMessageContaining("remains unbound");
    }
    @Test void anotherTenantsRoleCannotBeReconciled() {
        when(roles.findByTenantId(7L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.reconcileDeclaredRolesStrictly(manifest, 7L))
                .isInstanceOf(PluginException.class).hasMessageContaining("Imported role not found");
        verifyNoInteractions(importer, permissions);
    }
    @Test void rolelessPluginIsAnExplicitNoop() {
        manifest.setRoles(List.of());
        assertThat(service.reconcileDeclaredRolesStrictly(manifest, 7L)).isZero();
        verifyNoInteractions(importer, roles, permissions, bindings);
    }
}
