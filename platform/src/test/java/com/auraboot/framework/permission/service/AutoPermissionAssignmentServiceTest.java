package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoPermissionAssignmentServiceTest {

    @Mock
    private PermissionService permissionService;

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private RoleService roleService;

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private CommandActionDeriver commandActionDeriver;

    @Mock
    private UserPermissionService userPermissionService;

    @Test
    void shouldBindGeneratedPermissionsToExplicitTenant() {
        AutoPermissionAssignmentService service = new AutoPermissionAssignmentService(
                permissionService,
                permissionMapper,
                roleService,
                rolePermissionMapper,
                commandActionDeriver,
                userPermissionService
        );

        when(commandActionDeriver.deriveActions("crm_lead_common")).thenReturn(List.of("read", "create"));
        Map<String, Permission> definitions = new HashMap<>();
        when(permissionMapper.findByCode(any())).thenAnswer(invocation -> definitions.get(invocation.getArgument(0)));

        AtomicLong nextId = new AtomicLong(100);
        doAnswer(invocation -> {
            Permission permission = invocation.getArgument(0);
            permission.setId(nextId.getAndIncrement());
            definitions.put(permission.getCode(), permission);
            return 1;
        }).when(permissionMapper).insert(any(Permission.class));

        Role tenantAdmin = new Role();
        tenantAdmin.setId(88L);
        tenantAdmin.setCode("tenant_admin");
        tenantAdmin.setName("Tenant Admin");
        when(roleService.findByTenantId(123L)).thenReturn(List.of(tenantAdmin));

        when(rolePermissionMapper.findByRoleAndPermission(anyLong(), anyLong())).thenReturn(null);

        service.autoAssignPermissions("crm_lead_common", "crm", 123L);

        ArgumentCaptor<Permission> permissionCaptor = ArgumentCaptor.forClass(Permission.class);
        verify(permissionMapper, org.mockito.Mockito.times(4)).insert(permissionCaptor.capture());
        assertThat(permissionCaptor.getAllValues())
                .extracting(Permission::getDeletedFlag)
                .containsOnly(false);

        verify(roleService).findByTenantId(123L);
        verify(userPermissionService).evictPermissionDefinitions(123L);
        verify(userPermissionService).evictRoleUsers(123L, 88L);

        ArgumentCaptor<RolePermission> bindingCaptor = ArgumentCaptor.forClass(RolePermission.class);
        verify(rolePermissionMapper, org.mockito.Mockito.times(2)).insert(bindingCaptor.capture());

        List<RolePermission> bindings = bindingCaptor.getAllValues();
        assertThat(bindings).hasSize(2);
        assertThat(bindings)
                .extracting(RolePermission::getTenantId)
                .containsOnly(123L);
        assertThat(bindings)
                .extracting(RolePermission::getRoleId)
                .containsOnly(88L);
        assertThat(bindings)
                .extracting(RolePermission::getPermissionId)
                .doesNotContainNull();

        verify(permissionMapper).findByCode(eq("module.crm"));
        verify(permissionMapper).findByCode(eq("model.crm_lead_common"));
        verify(permissionMapper).findByCode(eq("model.crm_lead_common.read"));
        verify(permissionMapper).findByCode(eq("model.crm_lead_common.create"));

        // A synchronization must register new actions without restoring revoked grants
        // or applying the default template to an existing resource again.
        when(commandActionDeriver.deriveActions("crm_lead_common"))
                .thenReturn(List.of("read", "create", "export"));
        service.autoAssignPermissions("crm_lead_common", "crm", 123L);
        assertThat(definitions.keySet()).containsExactlyInAnyOrder(
                "module.crm", "model.crm_lead_common", "model.crm_lead_common.read",
                "model.crm_lead_common.create", "model.crm_lead_common.export");
        verify(permissionMapper, org.mockito.Mockito.times(5)).insert(any(Permission.class));
        verify(rolePermissionMapper, org.mockito.Mockito.times(2)).insert(any(RolePermission.class));
        verify(roleService, org.mockito.Mockito.times(1)).findByTenantId(123L);
        verify(userPermissionService, org.mockito.Mockito.times(2)).evictPermissionDefinitions(123L);
        verify(userPermissionService, org.mockito.Mockito.times(1)).evictRoleUsers(123L, 88L);
    }
    @Test
    void shouldRegisterImportedActionsWithoutGrantingRoles() {
        AutoPermissionAssignmentService service = new AutoPermissionAssignmentService(
                permissionService, permissionMapper, roleService, rolePermissionMapper,
                commandActionDeriver, userPermissionService);
        when(commandActionDeriver.deriveActions("crm_lead_common")).thenReturn(List.of("read", "create"));
        AtomicLong nextId = new AtomicLong(100);
        doAnswer(invocation -> {
            Permission permission = invocation.getArgument(0);
            permission.setId(nextId.getAndIncrement());
            return 1;
        }).when(permissionMapper).insert(any(Permission.class));

        service.registerPermissions("crm_lead_common", "crm", 123L);

        ArgumentCaptor<Permission> definitions = ArgumentCaptor.forClass(Permission.class);
        verify(permissionMapper, org.mockito.Mockito.times(4)).insert(definitions.capture());
        assertThat(definitions.getAllValues()).extracting(Permission::getCode)
                .containsExactly("module.crm", "model.crm_lead_common",
                        "model.crm_lead_common.read", "model.crm_lead_common.create");
        org.mockito.Mockito.verifyNoInteractions(roleService, rolePermissionMapper);
        verify(userPermissionService).evictPermissionDefinitions(123L);
    }

}
