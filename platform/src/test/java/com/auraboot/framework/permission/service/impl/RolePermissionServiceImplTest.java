package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.entity.RoleDataScope;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RolePermissionServiceImpl}.
 *
 * <p>Verifies the §P4 write-throws / read-fails-closed exception strategy
 * documented on the implementation.
 */
@ExtendWith(MockitoExtension.class)
class RolePermissionServiceImplTest {

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private UserPermissionService userPermissionService;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private DataScopeService dataScopeService;

    @InjectMocks
    private RolePermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "u", "t");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void assignPermissionsToRoleBatchInsertsAndEvictsCache() {
        boolean ok = service.assignPermissionsToRole(7L, List.of(50L, 51L));

        assertThat(ok).isTrue();
        ArgumentCaptor<List<RolePermission>> captor = ArgumentCaptor.forClass(List.class);
        verify(rolePermissionMapper).batchInsert(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getRoleId()).isEqualTo(7L);
        assertThat(captor.getValue().get(0).getDeletedFlag()).isFalse();
        verify(userPermissionService).evictRoleUsers(7L);
    }

    @Test
    void assignInheritsRoleDefaultScopeForNewGrants() {
        Role role = new Role();
        role.setId(7L);
        role.setDefaultDataScopeType("dept");
        when(roleMapper.selectById(7L)).thenReturn(role);
        when(dataScopeService.getScopesByRole(100L, 7L)).thenReturn(List.of());
        when(permissionMapper.findByIds(anyList())).thenReturn(List.of(
                perm(50L, "crm.account", "read"),
                perm(51L, "crm.lead", "manage")));

        service.assignPermissionsToRole(7L, List.of(50L, 51L));

        // each newly-granted (resource, action) inherits the role's default tier
        verify(dataScopeService).setScope(100L, 7L, "crm.account", "read", "dept", "MAX");
        verify(dataScopeService).setScope(100L, 7L, "crm.lead", "manage", "dept", "MAX");
    }

    @Test
    void assignDoesNotInheritWhenRoleHasNoDefault() {
        Role role = new Role();
        role.setId(7L);
        role.setDefaultDataScopeType(null); // opted out — preserve per-action deny-by-default
        when(roleMapper.selectById(7L)).thenReturn(role);

        service.assignPermissionsToRole(7L, List.of(50L));

        verify(dataScopeService, never())
                .setScope(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void assignDoesNotOverwriteExistingScopeOverride() {
        Role role = new Role();
        role.setId(7L);
        role.setDefaultDataScopeType("dept");
        when(roleMapper.selectById(7L)).thenReturn(role);
        RoleDataScope override = new RoleDataScope();
        override.setResourceCode("crm.account");
        override.setActionCode("read");
        when(dataScopeService.getScopesByRole(100L, 7L)).thenReturn(List.of(override));
        when(permissionMapper.findByIds(anyList())).thenReturn(List.of(
                perm(50L, "crm.account", "read"),   // already has an explicit override
                perm(51L, "crm.lead", "manage")));   // new -> inherits default

        service.assignPermissionsToRole(7L, List.of(50L, 51L));

        verify(dataScopeService, never())
                .setScope(eq(100L), eq(7L), eq("crm.account"), eq("read"), anyString(), anyString());
        verify(dataScopeService).setScope(100L, 7L, "crm.lead", "manage", "dept", "MAX");
    }

    @Test
    void inheritDefaultDataScopeMaterializesWithoutUpsertingBinding() {
        Role role = new Role();
        role.setId(7L);
        role.setDefaultDataScopeType("self");
        when(roleMapper.selectById(7L)).thenReturn(role);
        when(dataScopeService.getScopesByRole(100L, 7L)).thenReturn(List.of());
        when(permissionMapper.findByIds(anyList())).thenReturn(List.of(
                perm(50L, "crm.account", "read")));

        boolean ok = service.inheritDefaultDataScope(7L, List.of(50L));

        assertThat(ok).isTrue();
        verify(dataScopeService).setScope(100L, 7L, "crm.account", "read", "self", "MAX");
        verify(rolePermissionMapper, never()).batchInsert(anyList());
        verify(userPermissionService).evictRoleUsers(7L);
    }

    private Permission perm(Long id, String resourceCode, String action) {
        Permission p = new Permission();
        p.setId(id);
        p.setResourceCode(resourceCode);
        p.setAction(action);
        return p;
    }

    @Test
    void assignPermissionsToRoleSkipsBatchInsertWhenIdsEmpty() {
        boolean ok = service.assignPermissionsToRole(7L, List.of());

        assertThat(ok).isTrue();
        verify(rolePermissionMapper, never()).batchInsert(anyList());
        verify(userPermissionService).evictRoleUsers(7L);
    }

    @Test
    void assignPermissionsToRoleWrapsMapperFailureAsBusinessException() {
        when(rolePermissionMapper.batchInsert(anyList())).thenThrow(new RuntimeException("db"));

        assertThatThrownBy(() -> service.assignPermissionsToRole(7L, List.of(50L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void removePermissionReturnsTrueWhenRowsAffected() {
        when(rolePermissionMapper.deleteByRoleAndPermission(7L, 50L, 100L)).thenReturn(1);

        assertThat(service.removePermission(7L, 50L)).isTrue();
        verify(userPermissionService).evictRoleUsers(7L);
    }

    @Test
    void removePermissionReturnsFalseWhenNoRowsAffected() {
        when(rolePermissionMapper.deleteByRoleAndPermission(7L, 50L, 100L)).thenReturn(0);

        assertThat(service.removePermission(7L, 50L)).isFalse();
    }

    @Test
    void removePermissionWrapsErrors() {
        when(rolePermissionMapper.deleteByRoleAndPermission(anyLong(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("db"));

        assertThatThrownBy(() -> service.removePermission(7L, 50L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void removeAllPermissionsByRoleIdEvictsCache() {
        when(rolePermissionMapper.deleteByRoleId(7L, 100L)).thenReturn(3);

        assertThat(service.removeAllPermissionsByRoleId(7L)).isTrue();
        verify(userPermissionService).evictRoleUsers(7L);
    }

    @Test
    void removeAllPermissionsByRoleIdWrapsErrors() {
        when(rolePermissionMapper.deleteByRoleId(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("db"));

        assertThatThrownBy(() -> service.removeAllPermissionsByRoleId(7L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getPermissionIdsByRoleIdMapsBindings() {
        RolePermission rp1 = new RolePermission();
        rp1.setPermissionId(50L);
        RolePermission rp2 = new RolePermission();
        rp2.setPermissionId(51L);
        when(rolePermissionMapper.findByRoleId(7L, 100L)).thenReturn(List.of(rp1, rp2));

        Set<Long> ids = service.getPermissionIdsByRoleId(7L);

        assertThat(ids).containsExactlyInAnyOrder(50L, 51L);
    }

    @Test
    void getPermissionIdsByRoleIdFailsClosedOnError() {
        when(rolePermissionMapper.findByRoleId(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("db"));

        assertThat(service.getPermissionIdsByRoleId(7L)).isEmpty();
    }

    @Test
    void getPermissionPidsByRoleIdReturnsEmptyWhenNoIds() {
        when(rolePermissionMapper.findByRoleId(7L, 100L)).thenReturn(List.of());

        assertThat(service.getPermissionPidsByRoleId(7L)).isEmpty();
    }

    @Test
    void getPermissionPidsByRoleIdMapsPermissionsToPids() {
        RolePermission rp = new RolePermission();
        rp.setPermissionId(50L);
        when(rolePermissionMapper.findByRoleId(7L, 100L)).thenReturn(List.of(rp));
        Permission p = new Permission();
        p.setId(50L);
        p.setPid("perm-pid-1");
        when(permissionMapper.findByIds(anyList())).thenReturn(List.of(p));

        assertThat(service.getPermissionPidsByRoleId(7L)).containsExactly("perm-pid-1");
    }

    @Test
    void syncRolePermissionsByPidsRemovesAndAssigns() {
        when(rolePermissionMapper.deleteByRoleId(7L, 100L)).thenReturn(2);
        Permission p = new Permission();
        p.setId(50L);
        when(permissionMapper.findByPids(List.of("pid-50"))).thenReturn(List.of(p));

        boolean ok = service.syncRolePermissionsByPids(7L, List.of("pid-50"), "grant");

        assertThat(ok).isTrue();
        verify(rolePermissionMapper).batchInsert(anyList());
    }

    @Test
    void syncRolePermissionsByPidsReturnsTrueWhenNoPermissionsFound() {
        when(rolePermissionMapper.deleteByRoleId(7L, 100L)).thenReturn(0);
        when(permissionMapper.findByPids(anyList())).thenReturn(List.of());

        assertThat(service.syncRolePermissionsByPids(7L, List.of("missing"), "grant")).isTrue();
        verify(rolePermissionMapper, never()).batchInsert(anyList());
    }

    @Test
    void removePermissionsFromRoleByPidsHandlesEmptyMatch() {
        when(permissionMapper.findByPids(anyList())).thenReturn(List.of());

        assertThat(service.removePermissionsFromRoleByPids(7L, List.of("pid-x"))).isTrue();
    }

    @Test
    void removePermissionsFromRoleByPidsRemovesEachPermission() {
        Permission p = new Permission();
        p.setId(50L);
        when(permissionMapper.findByPids(anyList())).thenReturn(List.of(p));
        when(rolePermissionMapper.deleteByRoleAndPermission(7L, 50L, 100L)).thenReturn(1);

        assertThat(service.removePermissionsFromRoleByPids(7L, List.of("pid-50"))).isTrue();
        verify(rolePermissionMapper).deleteByRoleAndPermission(7L, 50L, 100L);
    }

    @Test
    void getRolePermissionStatisticsReturnsEmptyGroupsForEmptyRole() {
        when(rolePermissionMapper.findByRoleId(7L, 100L)).thenReturn(List.of());

        Map<String, Object> stats = service.getRolePermissionStatistics(7L);

        assertThat(stats).containsEntry("totalPermissions", 0);
        assertThat(stats).containsEntry("byResource", Map.of());
        assertThat(stats).containsEntry("byAction", Map.of());
    }

    @Test
    void getRolePermissionStatisticsGroupsByResourceAndAction() {
        RolePermission rp = new RolePermission();
        rp.setPermissionId(50L);
        when(rolePermissionMapper.findByRoleId(7L, 100L)).thenReturn(List.of(rp));
        Permission p = new Permission();
        p.setId(50L);
        p.setResourceType("MODEL");
        p.setAction("read");
        when(permissionMapper.findByIds(anyList())).thenReturn(List.of(p));

        Map<String, Object> stats = service.getRolePermissionStatistics(7L);

        assertThat(stats).containsEntry("totalPermissions", 1);
        @SuppressWarnings("unchecked")
        Map<String, Long> byResource = (Map<String, Long>) stats.get("byResource");
        assertThat(byResource).containsEntry("MODEL", 1L);
    }

    @Test
    void copyRolePermissionsSkipsWhenSourceEmpty() {
        when(rolePermissionMapper.findByRoleId(7L, 100L)).thenReturn(List.of());

        assertThat(service.copyRolePermissions(7L, 8L)).isTrue();
        verify(rolePermissionMapper, never()).batchInsert(anyList());
    }

    @Test
    void copyRolePermissionsAssignsToTarget() {
        RolePermission rp = new RolePermission();
        rp.setPermissionId(50L);
        when(rolePermissionMapper.findByRoleId(7L, 100L)).thenReturn(List.of(rp));

        assertThat(service.copyRolePermissions(7L, 8L)).isTrue();

        ArgumentCaptor<List<RolePermission>> captor = ArgumentCaptor.forClass(List.class);
        verify(rolePermissionMapper).batchInsert(captor.capture());
        assertThat(captor.getValue().get(0).getRoleId()).isEqualTo(8L);
    }
}
