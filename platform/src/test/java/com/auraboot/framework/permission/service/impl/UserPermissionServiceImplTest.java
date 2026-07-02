package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserPermissionServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class UserPermissionServiceImplTest {

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private Cache cache;

    @InjectMocks
    private UserPermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "u-pid", "tester");
        MetaContext.setMemberId(5L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void getUserPermissionIdsReturnsEmptyWhenTenantMissing() {
        MetaContext.clear();
        // No tenant context — service should warn and return empty (no exception)
        // Re-set member only after clear is impossible without tenant; this exercises
        // the tenant null-guard branch via direct call.
        try {
            assertThat(service.getUserPermissionIds(1L)).isEmpty();
        } catch (IllegalStateException expected) {
            // Acceptable: MetaContext.getCurrentTenantId() throws when context absent.
            // Either branch is a fail-closed posture.
        }
    }

    @Test
    void getUserPermissionIdsReturnsEmptyWhenMemberIdMissing() {
        MetaContext.setMemberId(null);

        assertThat(service.getUserPermissionIds(1L)).isEmpty();
        verify(userRoleMapper, never()).findByMemberIdAndTenantId(anyLong(), anyLong());
    }

    @Test
    void getUserPermissionIdsReturnsEmptyWhenNoRoles() {
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of());

        assertThat(service.getUserPermissionIds(1L)).isEmpty();
        verify(rolePermissionMapper, never()).findPermissionIdsByRoles(anyList());
    }

    @Test
    void getUserPermissionIdsReturnsPermissionIdsForRoles() {
        UserRole ur = new UserRole();
        ur.setRoleId(7L);
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(ur));
        when(rolePermissionMapper.findPermissionIdsByRoles(List.of(7L))).thenReturn(Set.of(50L, 51L));

        Set<Long> ids = service.getUserPermissionIds(1L);

        assertThat(ids).containsExactlyInAnyOrder(50L, 51L);
    }

    @Test
    void getUserPermissionIdsIncludesBaselineRolePermissions() {
        // Member has business role 7 (perm 50) AND the tenant implicitly grants the tenant_member
        // baseline role (role 99 -> perm 60). Effective set is the union — including the baseline
        // perm the member never had an ab_user_role row for.
        UserRole ur = new UserRole();
        ur.setRoleId(7L);
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(ur));
        when(rolePermissionMapper.findPermissionIdsByRoles(List.of(7L))).thenReturn(Set.of(50L));
        Role baseline = new Role();
        baseline.setId(99L);
        when(roleMapper.findByTenantIdAndCode(100L, "tenant_member")).thenReturn(baseline);
        when(rolePermissionMapper.findPermissionIdsByRoles(List.of(99L))).thenReturn(Set.of(60L));

        Set<Long> ids = service.getUserPermissionIds(1L);

        assertThat(ids).containsExactlyInAnyOrder(50L, 60L);
    }

    @Test
    void getUserPermissionIdsReturnsBaselineForMemberWithNoBusinessRoles() {
        // No ab_user_role rows, but the tenant_member baseline still applies (the original incident:
        // a member provisioned without the baseline could not render pages).
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of());
        Role baseline = new Role();
        baseline.setId(99L);
        when(roleMapper.findByTenantIdAndCode(100L, "tenant_member")).thenReturn(baseline);
        when(rolePermissionMapper.findPermissionIdsByRoles(List.of(99L))).thenReturn(Set.of(60L));

        Set<Long> ids = service.getUserPermissionIds(1L);

        assertThat(ids).containsExactly(60L);
    }

    @Test
    void evictUserPermissionsClearsCacheEntry() {
        when(cacheManager.getCache("user-permissions")).thenReturn(cache);

        service.evictUserPermissions(1L);

        verify(cache).evict(anyString());
    }

    @Test
    void evictUserPermissionsHandlesMissingCache() {
        when(cacheManager.getCache("user-permissions")).thenReturn(null);

        service.evictUserPermissions(1L); // must not throw
    }

    @Test
    void evictRoleUsersClearsAllWhenRoleHasMembers() {
        when(userRoleMapper.findMemberIdsByRoleId(7L)).thenReturn(List.of(5L, 6L));
        when(cacheManager.getCache("user-permissions")).thenReturn(cache);

        service.evictRoleUsers(7L);

        verify(cache).clear();
    }

    @Test
    void evictRoleUsersClearsEvenWhenRoleHasNoMembers() {
        // REG L1 (DDR-2026-06-30): the tenant_member baseline role is applied implicitly (no
        // ab_user_role rows), so a baseline grant change reports zero direct members yet must still
        // clear every member's cached permissions — evictRoleUsers must NOT early-return on empty.
        when(userRoleMapper.findMemberIdsByRoleId(7L)).thenReturn(List.of());
        when(cacheManager.getCache("user-permissions")).thenReturn(cache);

        service.evictRoleUsers(7L);

        verify(cache).clear();
    }

    @Test
    void batchGetUserPermissionIdsReturnsEmptyForNullInput() {
        assertThat(service.batchGetUserPermissionIds(null)).isEmpty();
        assertThat(service.batchGetUserPermissionIds(List.of())).isEmpty();
    }

    @Test
    void batchGetUserPermissionIdsReturnsPerUserPermissions() {
        UserRole ur = new UserRole();
        ur.setRoleId(7L);
        lenient().when(userRoleMapper.findByMemberIdAndTenantId(anyLong(), anyLong())).thenReturn(List.of(ur));
        lenient().when(rolePermissionMapper.findPermissionIdsByRoles(anyList())).thenReturn(Set.of(50L));

        Map<Long, Set<Long>> result = service.batchGetUserPermissionIds(List.of(1L, 2L));

        assertThat(result).hasSize(2);
        assertThat(result.get(1L)).containsExactly(50L);
    }

    @Test
    void hasPermissionByCodeReturnsFalseForInvalidInput() {
        assertThat(service.hasPermission(null, "code")).isFalse();
        assertThat(service.hasPermission(1L, (String) null)).isFalse();
        assertThat(service.hasPermission(1L, "")).isFalse();
    }

    @Test
    void hasPermissionByCodeReturnsFalseWhenCodeUnregistered() {
        when(permissionMapper.findByCode("missing.code")).thenReturn(null);

        assertThat(service.hasPermission(1L, "missing.code")).isFalse();
    }

    @Test
    void hasPermissionByCodeReturnsTrueWhenUserHasPermission() {
        Permission p = new Permission();
        p.setId(50L);
        when(permissionMapper.findByCode("model.user.read")).thenReturn(p);

        UserRole ur = new UserRole();
        ur.setRoleId(7L);
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(ur));
        when(rolePermissionMapper.findPermissionIdsByRoles(List.of(7L))).thenReturn(Set.of(50L));

        assertThat(service.hasPermission(1L, "model.user.read")).isTrue();
    }

    @Test
    void hasPermissionByIdReturnsFalseForNullInputs() {
        assertThat(service.hasPermission(null, 50L)).isFalse();
        assertThat(service.hasPermission(1L, (Long) null)).isFalse();
    }

    @Test
    void hasAllPermissionsReturnsTrueWhenUserHasAll() {
        UserRole ur = new UserRole();
        ur.setRoleId(7L);
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(ur));
        when(rolePermissionMapper.findPermissionIdsByRoles(List.of(7L))).thenReturn(Set.of(50L, 51L));

        assertThat(service.hasAllPermissions(1L, List.of(50L, 51L))).isTrue();
    }

    @Test
    void hasAllPermissionsReturnsFalseWhenMissingOne() {
        UserRole ur = new UserRole();
        ur.setRoleId(7L);
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(ur));
        when(rolePermissionMapper.findPermissionIdsByRoles(List.of(7L))).thenReturn(Set.of(50L));

        assertThat(service.hasAllPermissions(1L, List.of(50L, 51L))).isFalse();
    }

    @Test
    void hasAllPermissionsReturnsFalseForInvalidInputs() {
        assertThat(service.hasAllPermissions(null, List.of(1L))).isFalse();
        assertThat(service.hasAllPermissions(1L, null)).isFalse();
        assertThat(service.hasAllPermissions(1L, List.of())).isFalse();
    }

    @Test
    void hasAnyPermissionReturnsTrueWhenAtLeastOneMatch() {
        UserRole ur = new UserRole();
        ur.setRoleId(7L);
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(ur));
        when(rolePermissionMapper.findPermissionIdsByRoles(List.of(7L))).thenReturn(Set.of(50L));

        assertThat(service.hasAnyPermission(1L, List.of(99L, 50L))).isTrue();
    }

    @Test
    void hasAnyPermissionReturnsFalseWhenNoMatch() {
        UserRole ur = new UserRole();
        ur.setRoleId(7L);
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(ur));
        when(rolePermissionMapper.findPermissionIdsByRoles(List.of(7L))).thenReturn(Set.of(50L));

        assertThat(service.hasAnyPermission(1L, List.of(99L))).isFalse();
    }

    @Test
    void hasAnyPermissionReturnsFalseForInvalidInputs() {
        assertThat(service.hasAnyPermission(null, List.of(1L))).isFalse();
        assertThat(service.hasAnyPermission(1L, null)).isFalse();
        assertThat(service.hasAnyPermission(1L, List.of())).isFalse();
    }

    @Test
    void clearPermissionCodeCacheIsIdempotent() {
        service.clearPermissionCodeCache();
        service.clearPermissionCodeCache();
    }
}
