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

    @Test
    void shouldBindGeneratedPermissionsToExplicitTenant() {
        AutoPermissionAssignmentService service = new AutoPermissionAssignmentService(
                permissionService,
                permissionMapper,
                roleService,
                rolePermissionMapper,
                commandActionDeriver
        );

        when(commandActionDeriver.deriveActions("tcrm_lead")).thenReturn(List.of("read", "create"));
        when(permissionMapper.findByCode(any())).thenReturn(null);

        AtomicLong nextId = new AtomicLong(100);
        doAnswer(invocation -> {
            Permission permission = invocation.getArgument(0);
            permission.setId(nextId.getAndIncrement());
            return 1;
        }).when(permissionMapper).insert(any(Permission.class));

        Role tenantAdmin = new Role();
        tenantAdmin.setId(88L);
        tenantAdmin.setCode("tenant_admin");
        tenantAdmin.setName("Tenant Admin");
        when(roleService.findByTenantId(123L)).thenReturn(List.of(tenantAdmin));

        when(rolePermissionMapper.findByRoleAndPermission(anyLong(), anyLong())).thenReturn(null);

        service.autoAssignPermissions("tcrm_lead", "tcrm", 123L);

        verify(roleService).findByTenantId(123L);

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

        verify(permissionMapper).findByCode(eq("module.tcrm"));
        verify(permissionMapper).findByCode(eq("model.tcrm_lead"));
        verify(permissionMapper).findByCode(eq("model.tcrm_lead.read"));
        verify(permissionMapper).findByCode(eq("model.tcrm_lead.create"));
    }
}
