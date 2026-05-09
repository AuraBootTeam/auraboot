package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoleServiceImpl")
class RoleServiceImplTest {

    @Mock private RoleMapper roleMapper;
    @Mock private RolePermissionService rolePermissionService;
    @Mock private UserRoleService userRoleService;
    @Mock private PermissionService permissionService;
    @Mock private PermissionMapper permissionMapper;

    private RoleServiceImpl service;
    private RoleServiceImpl spyService;

    @BeforeEach
    void setUp() throws Exception {
        service = new RoleServiceImpl();
        injectField(service, "baseMapper", roleMapper);
        injectField(service, "roleMapper", roleMapper);
        injectField(service, "rolePermissionService", rolePermissionService);
        injectField(service, "userRoleService", userRoleService);
        injectField(service, "permissionService", permissionService);
        injectField(service, "permissionMapper", permissionMapper);
        spyService = spy(service);
    }

    static void injectField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private Role role(Long id, String code) {
        Role r = new Role();
        r.setId(id);
        r.setCode(code);
        r.setName("name-" + id);
        r.setTenantId(10L);
        return r;
    }

    @Test
    @DisplayName("createRole rejects platform_admin in non-global scope")
    void createRolePlatformAdminBlocked() {
        Role r = role(null, "platform_admin");
        r.setScopeType("tenant");
        assertThrows(BusinessException.class, () -> spyService.createRole(r));
    }

    @Test
    @DisplayName("createRole sets defaults and saves")
    void createRoleHappy() {
        Role r = role(null, "rolex");
        doReturn(true).when(spyService).save(r);
        Role out = spyService.createRole(r);
        assertEquals(StatusConstants.ACTIVE, out.getStatus());
        assertNotNull(out.getPid());
        assertFalse(out.getDeletedFlag());
        assertFalse(out.getIsSystem());
    }

    @Test
    @DisplayName("updateRole throws when role not found")
    void updateRoleMissing() {
        Role r = role(1L, "x");
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.updateRole(r));
    }

    @Test
    @DisplayName("updateRole forces isSystem=true for system roles")
    void updateRoleSystemFlagPreserved() {
        Role existing = role(1L, "x");
        existing.setIsSystem(true);
        Role input = role(1L, "x");
        input.setIsSystem(false);
        doReturn(existing).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(input);

        spyService.updateRole(input);
        assertTrue(input.getIsSystem());
    }

    @Test
    @DisplayName("findByPid delegates to mapper")
    void findByPidDelegates() {
        Role r = role(1L, "x");
        when(roleMapper.findByPid("p1")).thenReturn(r);
        assertEquals(r, service.findByPid("p1"));
    }

    @Test
    @DisplayName("findByTenantId delegates to mapper")
    void findByTenantIdDelegates() {
        when(roleMapper.findByTenantId(10L)).thenReturn(List.of(role(1L, "x")));
        assertEquals(1, service.findByTenantId(10L).size());
    }

    @Test
    @DisplayName("findByTenantIdAndType delegates to mapper")
    void findByTenantIdAndTypeDelegates() {
        when(roleMapper.findByTenantIdAndType(10L, "business")).thenReturn(List.of());
        assertEquals(0, service.findByTenantIdAndType(10L, "business").size());
    }

    @Test
    @DisplayName("findRoles applies filters")
    void findRolesAppliesFilters() {
        Page<Role> page = new Page<>(1, 10);
        doReturn(page).when(spyService).page(any(Page.class), any(QueryWrapper.class));
        Page<Role> out = spyService.findRoles(1, 10, 10L, "kw", "business", "active");
        assertNotNull(out);
        verify(spyService).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    @DisplayName("findRoles with all blank filters still returns page")
    void findRolesBlankFilters() {
        Page<Role> page = new Page<>(1, 10);
        doReturn(page).when(spyService).page(any(Page.class), any(QueryWrapper.class));
        assertNotNull(spyService.findRoles(1, 10, null, null, null, null));
    }

    @Test
    @DisplayName("findDefaultRole delegates to mapper")
    void findDefaultRoleDelegates() {
        Role r = role(1L, "x");
        when(roleMapper.findDefaultRole(10L)).thenReturn(r);
        assertEquals(r, service.findDefaultRole(10L));
    }

    @Test
    @DisplayName("enableRole throws when missing")
    void enableRoleMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.enableRole(1L));
    }

    @Test
    @DisplayName("enableRole sets ACTIVE and updates")
    void enableRoleHappy() {
        Role r = role(1L, "x");
        r.setStatus(StatusConstants.INACTIVE);
        doReturn(r).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(r);
        assertTrue(spyService.enableRole(1L));
        assertEquals(StatusConstants.ACTIVE, r.getStatus());
    }

    @Test
    @DisplayName("disableRole throws when missing or system role")
    void disableRoleBadCases() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.disableRole(1L));

        Role sys = role(2L, "x");
        sys.setIsSystem(true);
        doReturn(sys).when(spyService).getById(2L);
        assertThrows(BusinessException.class, () -> spyService.disableRole(2L));
    }

    @Test
    @DisplayName("disableRole sets INACTIVE for non-system role")
    void disableRoleHappy() {
        Role r = role(1L, "x");
        r.setIsSystem(false);
        doReturn(r).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(r);
        assertTrue(spyService.disableRole(1L));
        assertEquals(StatusConstants.INACTIVE, r.getStatus());
    }

    @Test
    @DisplayName("deleteRole rejects missing/system/in-use roles")
    void deleteRoleBadCases() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.deleteRole(1L));

        Role sys = role(2L, "x"); sys.setIsSystem(true);
        doReturn(sys).when(spyService).getById(2L);
        assertThrows(BusinessException.class, () -> spyService.deleteRole(2L));

        Role inUse = role(3L, "x"); inUse.setIsSystem(false);
        doReturn(inUse).when(spyService).getById(3L);
        when(userRoleService.countByRoleId(3L)).thenReturn(5L);
        assertThrows(BusinessException.class, () -> spyService.deleteRole(3L));
    }

    @Test
    @DisplayName("deleteRole removes permissions and deletes record")
    void deleteRoleHappy() {
        Role r = role(1L, "x"); r.setIsSystem(false);
        doReturn(r).when(spyService).getById(1L);
        when(userRoleService.countByRoleId(1L)).thenReturn(0L);
        when(roleMapper.deleteById(anyLong())).thenReturn(1);

        assertTrue(spyService.deleteRole(1L));
        verify(rolePermissionService).removeAllPermissionsByRoleId(1L);
    }

    @Test
    @DisplayName("isCodeAvailable true when count == 0")
    void isCodeAvailableTrue() {
        doReturn(0L).when(spyService).count(any(QueryWrapper.class));
        assertTrue(spyService.isCodeAvailable("new", 10L));
    }

    @Test
    @DisplayName("isCodeAvailable false when count > 0")
    void isCodeAvailableFalse() {
        doReturn(1L).when(spyService).count(any(QueryWrapper.class));
        assertFalse(spyService.isCodeAvailable("dup", 10L));
    }

    @Test
    @DisplayName("assignPermissions removes existing then assigns new")
    void assignPermissionsHappy() {
        Role r = role(1L, "x");
        doReturn(r).when(spyService).getById(1L);
        assertTrue(spyService.assignPermissions(1L, List.of(101L, 102L)));
        verify(rolePermissionService).removeAllPermissionsByRoleId(1L);
        verify(rolePermissionService).assignPermissionsToRole(1L, List.of(101L, 102L));
    }

    @Test
    @DisplayName("assignPermissions throws when role missing")
    void assignPermissionsMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.assignPermissions(1L, List.of(101L)));
    }

    @Test
    @DisplayName("removePermissions removes each id")
    void removePermissions() {
        assertTrue(service.removePermissions(1L, List.of(101L, 102L)));
        verify(rolePermissionService).removePermission(1L, 101L);
        verify(rolePermissionService).removePermission(1L, 102L);
    }

    @Test
    @DisplayName("getRolePermissionIds returns list from set")
    void getRolePermissionIds() {
        when(rolePermissionService.getPermissionIdsByRoleId(1L)).thenReturn(Set.of(11L, 22L));
        assertEquals(2, service.getRolePermissionIds(1L).size());
    }

    @Test
    @DisplayName("assignRoleToMember saves UserRole")
    void assignRoleToMember() {
        when(userRoleService.save(any())).thenReturn(true);
        assertTrue(service.assignRoleToMember(1L, 2L, 10L));
    }

    @Test
    @DisplayName("removeRoleFromMember delegates to userRoleService")
    void removeRoleFromMember() {
        when(userRoleService.removeMemberRole(1L, 2L, 10L)).thenReturn(true);
        assertTrue(service.removeRoleFromMember(1L, 2L, 10L));
    }

    @Test
    @DisplayName("countByTenantId delegates to mapper")
    void countByTenantId() {
        when(roleMapper.countByTenantId(10L)).thenReturn(3L);
        assertEquals(3L, service.countByTenantId(10L));
    }

    @Test
    @DisplayName("copyRole throws when source missing")
    void copyRoleMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.copyRole(1L, "newName", "newCode"));
    }

    @Test
    @DisplayName("copyRole throws when newCode already used")
    void copyRoleCodeDup() {
        Role src = role(1L, "src");
        doReturn(src).when(spyService).getById(1L);
        doReturn(1L).when(spyService).count(any(QueryWrapper.class));
        assertThrows(BusinessException.class, () -> spyService.copyRole(1L, "newName", "dupCode"));
    }

    @Test
    @DisplayName("copyRole creates new role with copied attributes")
    void copyRoleHappy() {
        Role src = role(1L, "src");
        src.setType("business");
        src.setPriority(5);
        doReturn(src).when(spyService).getById(1L);
        doReturn(0L).when(spyService).count(any(QueryWrapper.class));
        doReturn(true).when(spyService).save(any(Role.class));
        when(rolePermissionService.getPermissionIdsByRoleId(1L)).thenReturn(Set.of());

        Role copied = spyService.copyRole(1L, "newName", "newCode");
        assertEquals("newName", copied.getName());
        assertEquals("newCode", copied.getCode());
        assertEquals("business", copied.getType());
    }

    @Test
    @DisplayName("createDefaultRolesForTenant creates admin role")
    void createDefaultRolesForTenant() {
        doReturn(true).when(spyService).save(any(Role.class));
        spyService.createDefaultRolesForTenant(10L);
        verify(spyService).save(any(Role.class));
    }

    @Test
    @DisplayName("getRoleStatistics returns map of stats")
    void getRoleStatistics() {
        when(roleMapper.countByTenantId(10L)).thenReturn(5L);
        doReturn(List.of(Map.<String, Object>of("type", "business", "count", 3L)))
                .when(spyService).listMaps(any(QueryWrapper.class));

        Map<String, Object> out = spyService.getRoleStatistics(10L);
        assertEquals(5L, out.get("totalRoles"));
        assertNotNull(out.get("rolesByType"));
        assertNotNull(out.get("rolesByStatus"));
    }

    @Test
    @DisplayName("getRoleHierarchy sorts by priority")
    void getRoleHierarchy() {
        Role r1 = role(1L, "a"); r1.setPriority(2);
        Role r2 = role(2L, "b"); r2.setPriority(1);
        when(roleMapper.findByTenantId(10L)).thenReturn(List.of(r1, r2));
        when(rolePermissionService.getPermissionIdsByRoleId(any())).thenReturn(Set.of());

        List<Map<String, Object>> hierarchy = spyService.getRoleHierarchy(10L);
        assertEquals(2L, hierarchy.get(0).get("id"));
        assertEquals(1L, hierarchy.get(1).get("id"));
    }

    @Test
    @DisplayName("initializeSystemRoles skips when system roles exist")
    void initializeSystemRolesSkip() {
        doReturn(1L).when(spyService).count(any(QueryWrapper.class));
        spyService.initializeSystemRoles();
        verify(spyService, org.mockito.Mockito.never()).save(any(Role.class));
    }

    @Test
    @DisplayName("initializeSystemRoles creates super_admin and assigns all permissions")
    void initializeSystemRolesHappy() {
        doReturn(0L).when(spyService).count(any(QueryWrapper.class));
        doReturn(true).when(spyService).save(any(Role.class));

        Permission p = new Permission();
        p.setId(101L);
        when(permissionMapper.findByStatus(StatusConstants.ACTIVE)).thenReturn(List.of(p));

        spyService.initializeSystemRoles();
        verify(rolePermissionService).removeAllPermissionsByRoleId(any());
    }
}
