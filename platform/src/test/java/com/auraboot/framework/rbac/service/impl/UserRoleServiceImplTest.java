package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserRoleServiceImpl")
class UserRoleServiceImplTest {

    @Mock private UserRoleMapper userRoleMapper;

    private UserRoleServiceImpl service;
    private UserRoleServiceImpl spyService;

    @BeforeEach
    void setUp() throws Exception {
        service = new UserRoleServiceImpl();
        injectField(service, "baseMapper", userRoleMapper);
        injectField(service, "userRoleMapper", userRoleMapper);
        spyService = spy(service);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
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

    private UserRole ur(Long id, Long memberId, Long roleId, Long tenantId) {
        UserRole r = new UserRole();
        r.setId(id);
        r.setMemberId(memberId);
        r.setRoleId(roleId);
        r.setTenantId(tenantId);
        r.setStatus(StatusConstants.ACTIVE);
        return r;
    }

    @Test
    @DisplayName("assignRolesToMember returns true on empty roleIds")
    void assignEmpty() {
        assertTrue(service.assignRolesToMember(1L, List.of(), 10L, 99L));
    }

    @Test
    @DisplayName("assignRolesToMember creates new associations and skips existing")
    void assignSkipExisting() {
        when(userRoleMapper.findByMemberIdAndRoleIdAndTenantId(1L, 100L, 10L)).thenReturn(ur(11L, 1L, 100L, 10L));
        when(userRoleMapper.findByMemberIdAndRoleIdAndTenantId(1L, 200L, 10L)).thenReturn(null);
        doReturn(true).when(spyService).saveBatch(anyList());

        assertTrue(spyService.assignRolesToMember(1L, List.of(100L, 200L), 10L, 99L));
        verify(spyService).saveBatch(anyList());
    }

    @Test
    @DisplayName("assignRolesToMember returns true without save when all exist")
    void assignAllExist() {
        when(userRoleMapper.findByMemberIdAndRoleIdAndTenantId(any(), any(), any())).thenReturn(ur(1L, 1L, 100L, 10L));
        assertTrue(spyService.assignRolesToMember(1L, List.of(100L), 10L, 99L));
        verify(spyService, never()).saveBatch(anyList());
    }

    @Test
    @DisplayName("removeRolesFromMember returns true on empty roleIds")
    void removeEmpty() {
        assertTrue(service.removeRolesFromMember(1L, List.of(), 10L));
    }

    @Test
    @DisplayName("removeRolesFromMember delegates to remove")
    void removeHappy() {
        doReturn(true).when(spyService).remove(any(QueryWrapper.class));
        assertTrue(spyService.removeRolesFromMember(1L, List.of(100L), 10L));
    }

    @Test
    @DisplayName("removeAllRolesFromMemberInTenant uses mapper soft-delete")
    void removeAllInTenant() {
        when(userRoleMapper.deleteByMemberIdAndTenantId(1L, 10L)).thenReturn(2);
        assertTrue(service.removeAllRolesFromMemberInTenant(1L, 10L));
    }

    @Test
    @DisplayName("findByMemberIdAndTenantId delegates")
    void findByMemberIdAndTenantId() {
        when(userRoleMapper.findByMemberIdAndTenantId(1L, 10L)).thenReturn(List.of(ur(1L, 1L, 100L, 10L)));
        assertEquals(1, service.findByMemberIdAndTenantId(1L, 10L).size());
    }

    @Test
    @DisplayName("findByMemberIdAndRoleIdAndTenantId delegates")
    void findByMemberIdAndRoleIdAndTenantId() {
        UserRole r = ur(1L, 1L, 100L, 10L);
        when(userRoleMapper.findByMemberIdAndRoleIdAndTenantId(1L, 100L, 10L)).thenReturn(r);
        assertEquals(r, service.findByMemberIdAndRoleIdAndTenantId(1L, 100L, 10L));
    }

    @Test
    @DisplayName("findByPid delegates")
    void findByPid() {
        UserRole r = ur(1L, 1L, 100L, 10L);
        when(userRoleMapper.findByPid("p1")).thenReturn(r);
        assertEquals(r, service.findByPid("p1"));
    }

    @Test
    @DisplayName("findUserRoles applies filters")
    void findUserRoles() {
        Page<UserRole> p = new Page<>(1, 10);
        doReturn(p).when(spyService).page(any(Page.class), any(QueryWrapper.class));
        assertNotNull(spyService.findUserRoles(1, 10, 1L, 100L, 10L, 5L));
    }

    @Test
    @DisplayName("countByTenantId delegates to mapper")
    void countByTenantId() {
        when(userRoleMapper.countByTenantId(10L)).thenReturn(3);
        assertEquals(3L, service.countByTenantId(10L));
    }

    @Test
    @DisplayName("countByMemberId/countByRoleId return 0 (stubbed)")
    void countStubbed() {
        assertEquals(0, service.countByMemberId(1L));
        assertEquals(0, service.countByRoleId(100L));
    }

    @Test
    @DisplayName("batchAssignRoles returns 0 on empty")
    void batchAssignEmpty() {
        assertEquals(0, service.batchAssignRoles(List.of()));
    }

    @Test
    @DisplayName("batchAssignRoles saves with default ACTIVE status")
    void batchAssignHappy() {
        UserRole r = ur(null, 1L, 100L, 10L);
        r.setStatus(null);
        doReturn(true).when(spyService).saveBatch(anyList());
        assertEquals(1, spyService.batchAssignRoles(List.of(r)));
        assertEquals(StatusConstants.ACTIVE, r.getStatus());
    }

    @Test
    @DisplayName("batchRemoveRoles rejects cross-tenant ids")
    void batchRemoveCrossTenant() {
        MetaContext.setContext(10L, 1L, "u-1", "user");
        doReturn(List.of(ur(1L, 1L, 100L, 99L))).when(spyService).listByIds(anyList());
        assertThrows(BusinessException.class, () -> spyService.batchRemoveRoles(List.of(1L)));
    }

    @Test
    @DisplayName("batchRemoveRoles removes when same-tenant")
    void batchRemoveHappy() {
        MetaContext.setContext(10L, 1L, "u-1", "user");
        doReturn(List.of(ur(1L, 1L, 100L, 10L))).when(spyService).listByIds(anyList());
        doReturn(true).when(spyService).removeByIds(anyList());
        assertEquals(1, spyService.batchRemoveRoles(List.of(1L)));
    }

    @Test
    @DisplayName("batchRemoveRoles returns 0 on empty")
    void batchRemoveEmpty() {
        assertEquals(0, service.batchRemoveRoles(List.of()));
    }

    @Test
    @DisplayName("copyMemberRoles returns true when source has no roles")
    void copyMemberRolesEmpty() {
        when(userRoleMapper.findByMemberIdAndTenantId(1L, 10L)).thenReturn(List.of());
        assertTrue(service.copyMemberRoles(1L, 2L, 10L));
    }

    @Test
    @DisplayName("copyMemberRoles copies and saves batch")
    void copyMemberRolesHappy() {
        when(userRoleMapper.findByMemberIdAndTenantId(1L, 10L)).thenReturn(List.of(ur(1L, 1L, 100L, 10L)));
        when(userRoleMapper.deleteByMemberIdAndTenantId(2L, 10L)).thenReturn(1);
        doReturn(true).when(spyService).saveBatch(anyList());
        assertTrue(spyService.copyMemberRoles(1L, 2L, 10L));
    }

    @Test
    @DisplayName("syncMemberRoles adds and removes diffs")
    void syncMemberRoles() {
        when(userRoleMapper.findByMemberIdAndTenantId(1L, 10L)).thenReturn(List.of(ur(1L, 1L, 100L, 10L)));
        when(userRoleMapper.findByMemberIdAndRoleIdAndTenantId(1L, 200L, 10L)).thenReturn(null);
        doReturn(true).when(spyService).saveBatch(anyList());
        doReturn(true).when(spyService).remove(any(QueryWrapper.class));

        assertTrue(spyService.syncMemberRoles(1L, List.of(200L), 10L, 99L));
    }

    @Test
    @DisplayName("getRoleIdsByMemberIdAndTenantId returns role IDs")
    void getRoleIdsByMember() {
        when(userRoleMapper.findByMemberIdAndTenantId(1L, 10L)).thenReturn(List.of(ur(1L, 1L, 100L, 10L), ur(2L, 1L, 200L, 10L)));
        assertEquals(List.of(100L, 200L), service.getRoleIdsByMemberIdAndTenantId(1L, 10L));
    }

    @Test
    @DisplayName("isRoleInUse always false (stub returns 0)")
    void isRoleInUseStub() {
        assertFalse(service.isRoleInUse(100L));
    }

    @Test
    @DisplayName("isRoleInUseInTenant true when count > 0")
    void isRoleInUseInTenantTrue() {
        doReturn(2L).when(spyService).count(any(QueryWrapper.class));
        assertTrue(spyService.isRoleInUseInTenant(100L, 10L));
    }

    @Test
    @DisplayName("getTenantUserRoles delegates to mapper")
    void getTenantUserRoles() {
        when(userRoleMapper.getTenantUserRoles(10L)).thenReturn(List.of());
        assertEquals(0, service.getTenantUserRoles(10L).size());
    }

    @Test
    @DisplayName("validateMemberRoles flags warnings/errors")
    void validateMemberRoles() {
        UserRole bad = ur(1L, 1L, null, 10L);
        bad.setStatus(StatusConstants.INACTIVE);
        when(userRoleMapper.findByMemberIdAndTenantId(1L, 10L)).thenReturn(List.of(bad));
        var out = service.validateMemberRoles(1L, 10L);
        assertEquals(false, out.get("valid"));
    }

    @Test
    @DisplayName("validateMemberRoles warns when no roles")
    void validateMemberRolesNoRoles() {
        when(userRoleMapper.findByMemberIdAndTenantId(1L, 10L)).thenReturn(List.of());
        var out = service.validateMemberRoles(1L, 10L);
        assertEquals(true, out.get("valid"));
    }

    @Test
    @DisplayName("findByMemberIds/findByRoleIds return empty for empty input")
    void findByIdsEmpty() {
        assertTrue(service.findByMemberIds(List.of()).isEmpty());
        assertTrue(service.findByRoleIds(List.of()).isEmpty());
    }

    @Test
    @DisplayName("findByMemberIds delegates to base list")
    void findByMemberIds() {
        doReturn(List.of(ur(1L, 1L, 100L, 10L))).when(spyService).list(any(QueryWrapper.class));
        assertEquals(1, spyService.findByMemberIds(List.of(1L)).size());
    }

    @Test
    @DisplayName("transferMemberRolesToTenant returns true on empty")
    void transferEmpty() {
        when(userRoleMapper.findByMemberIdAndTenantId(1L, 10L)).thenReturn(List.of());
        assertTrue(service.transferMemberRolesToTenant(1L, 10L, 20L));
    }

    @Test
    @DisplayName("transferMemberRolesToTenant updates records")
    void transferUpdates() {
        when(userRoleMapper.findByMemberIdAndTenantId(1L, 10L)).thenReturn(List.of(ur(1L, 1L, 100L, 10L)));
        doReturn(true).when(spyService).update(any(UpdateWrapper.class));
        assertTrue(spyService.transferMemberRolesToTenant(1L, 10L, 20L));
    }

    @Test
    @DisplayName("activateUserRole/deactivateUserRole update status")
    void activateDeactivate() {
        doReturn(true).when(spyService).update(any(UpdateWrapper.class));
        assertTrue(spyService.activateUserRole(1L));
        assertTrue(spyService.deactivateUserRole(1L));
    }

    @Test
    @DisplayName("batchActivate/batchDeactivate return list size on success")
    void batchActivateDeactivate() {
        doReturn(true).when(spyService).update(any(UpdateWrapper.class));
        assertEquals(2, spyService.batchActivateUserRoles(List.of(1L, 2L)));
        assertEquals(2, spyService.batchDeactivateUserRoles(List.of(1L, 2L)));
    }

    @Test
    @DisplayName("batchActivate empty returns 0")
    void batchActivateEmpty() {
        assertEquals(0, service.batchActivateUserRoles(List.of()));
        assertEquals(0, service.batchDeactivateUserRoles(List.of()));
    }

    @Test
    @DisplayName("removeMemberRole delegates to remove")
    void removeMemberRole() {
        doReturn(true).when(spyService).remove(any(QueryWrapper.class));
        assertTrue(spyService.removeMemberRole(1L, 2L, 10L));
    }

    @Test
    @DisplayName("getMemberRoleHistory and cleanupInvalidUserRoles return defaults")
    void stubbedMethods() {
        assertEquals(0, service.getMemberRoleHistory(1L, 10L, 7).size());
        assertEquals(0, service.cleanupInvalidUserRoles());
    }
}
