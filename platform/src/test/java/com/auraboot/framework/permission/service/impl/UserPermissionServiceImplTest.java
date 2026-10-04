package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the permission service boundary and fail-closed context guards. */
@ExtendWith(MockitoExtension.class)
class UserPermissionServiceImplTest {

    @Mock
    private com.auraboot.framework.application.security.AdminRoleChecker adminRoleChecker;

    @Mock
    private PermissionSnapshotCache permissionSnapshotCache;

    @Mock
    private com.auraboot.framework.rbac.mapper.RoleMapper roleMapper;

    @Mock
    private com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog applicationRuntimeDefinitionCatalog;

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
    void permissionResolutionFailsClosedWithoutTenantContext() {
        MetaContext.clear();

        assertThat(service.getUserPermissionIds(1L)).isEmpty();
        assertThat(service.hasPermission(1L, "model.user.read")).isFalse();
        verify(permissionSnapshotCache, never()).getEffectivePermissionIds(100L, 1L, 5L);
    }

    @Test
    void permissionResolutionFailsClosedWithoutMemberContext() {
        MetaContext.setMemberId(null);

        assertThat(service.getUserPermissionIds(1L)).isEmpty();
        verify(permissionSnapshotCache, never()).getEffectivePermissionIds(100L, 1L, 5L);
    }

    @Test
    void getUserPermissionIdsDelegatesToAtomicSnapshotCache() {
        when(permissionSnapshotCache.getEffectivePermissionIds(100L, 1L, 5L))
                .thenReturn(Set.of(50L, 51L));

        assertThat(service.getUserPermissionIds(1L)).containsExactlyInAnyOrder(50L, 51L);
        verify(permissionSnapshotCache).getEffectivePermissionIds(100L, 1L, 5L);
    }

    @Test
    void permissionCodeResolutionUsesTenantCatalogAndEffectiveSnapshot() {
        when(permissionSnapshotCache.resolvePermissionId(100L, "model.user.read"))
                .thenReturn(50L);
        when(permissionSnapshotCache.getEffectivePermissionIds(100L, 1L, 5L))
                .thenReturn(Set.of(50L));

        assertThat(service.hasPermission(1L, "model.user.read")).isTrue();
    }

    @Test
    void permissionCodeResolutionIncludesBoundReleaseRoleDeclarations() {
        com.auraboot.framework.rbac.entity.Role role = new com.auraboot.framework.rbac.entity.Role();
        role.setCode("xy_school_admin");
        when(roleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(role));
        when(applicationRuntimeDefinitionCatalog.permissionsForRoles(
                100L, "aura-edu", Set.of("xy_school_admin"))).thenReturn(Set.of("xy.school.manage"));
        ReflectionTestUtils.setField(service, "defaultApplicationCode", "aura-edu");
        ReflectionTestUtils.setField(service, "applicationRuntimePrimaryEnabled", true);

        assertThat(service.hasPermission(1L, "xy.school.manage")).isTrue();
    }

    @Test
    void tenantAdminIncludesEveryPermissionDeclaredByTheBoundRelease() {
        com.auraboot.framework.rbac.entity.Role role = new com.auraboot.framework.rbac.entity.Role();
        role.setCode("tenant_admin");
        when(roleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(role));
        when(applicationRuntimeDefinitionCatalog.permissionCodes(100L, "aura-edu"))
                .thenReturn(Set.of("model.xy_reward_sku.read"));
        ReflectionTestUtils.setField(service, "defaultApplicationCode", "aura-edu");
        ReflectionTestUtils.setField(service, "applicationRuntimePrimaryEnabled", true);

        assertThat(service.hasPermission(1L, "model.xy_reward_sku.read")).isTrue();
    }

    @Test
    void explicitMemberPermissionCheckDoesNotReuseCurrentActorMember() {
        when(permissionSnapshotCache.resolvePermissionId(200L, "admin.customer.impersonate"))
                .thenReturn(70L);
        when(permissionSnapshotCache.getEffectivePermissionIds(200L, 2L, 9L))
                .thenReturn(Set.of());

        assertThat(service.hasPermission(200L, 2L, 9L, "admin.customer.impersonate"))
                .isFalse();
        verify(permissionSnapshotCache).getEffectivePermissionIds(200L, 2L, 9L);
        verify(permissionSnapshotCache, never()).getEffectivePermissionIds(200L, 2L, 5L);
    }

    @Test
    void explicitReleasePermissionsUseTheTargetMemberAndTenant() {
        com.auraboot.framework.rbac.entity.Role role = new com.auraboot.framework.rbac.entity.Role();
        role.setCode("xy_teacher");
        when(roleMapper.findByMemberIdAndTenantId(9L, 200L)).thenReturn(List.of(role));
        when(applicationRuntimeDefinitionCatalog.permissionsForRoles(
                200L, "aura-edu", Set.of("xy_teacher"))).thenReturn(Set.of("xy.score.submit"));
        ReflectionTestUtils.setField(service, "defaultApplicationCode", "aura-edu");
        ReflectionTestUtils.setField(service, "applicationRuntimePrimaryEnabled", true);

        assertThat(service.hasPermission(200L, 2L, 9L, "xy.score.submit")).isTrue();
        verify(roleMapper, never()).findByMemberIdAndTenantId(5L, 100L);
        verify(permissionSnapshotCache, never()).getEffectivePermissionIds(100L, 1L, 5L);
    }

    @Test
    void unknownPermissionCodeFailsClosedWithoutLoadingUserSnapshot() {
        when(permissionSnapshotCache.resolvePermissionId(100L, "missing.code")).thenReturn(null);

        assertThat(service.hasPermission(1L, "missing.code")).isFalse();
        verify(permissionSnapshotCache, never()).getEffectivePermissionIds(100L, 1L, 5L);
    }

    @Test
    void permissionChecksHandleInvalidInputs() {
        assertThat(service.hasPermission(null, "code")).isFalse();
        assertThat(service.hasPermission(1L, (String) null)).isFalse();
        assertThat(service.hasPermission(1L, "")).isFalse();
        assertThat(service.hasPermission(null, 50L)).isFalse();
        assertThat(service.hasPermission(1L, (Long) null)).isFalse();
    }

    @Test
    void allAndAnyChecksReuseResolvedSnapshot() {
        when(permissionSnapshotCache.getEffectivePermissionIds(100L, 1L, 5L))
                .thenReturn(Set.of(50L, 51L));

        assertThat(service.hasAllPermissions(1L, List.of(50L, 51L))).isTrue();
        assertThat(service.hasAnyPermission(1L, List.of(99L, 50L))).isTrue();
        assertThat(service.hasAnyPermission(1L, List.of(98L, 99L))).isFalse();
    }

    @Test
    void allAndAnyChecksRejectEmptyInput() {
        assertThat(service.hasAllPermissions(null, List.of(1L))).isFalse();
        assertThat(service.hasAllPermissions(1L, null)).isFalse();
        assertThat(service.hasAllPermissions(1L, List.of())).isFalse();
        assertThat(service.hasAnyPermission(null, List.of(1L))).isFalse();
        assertThat(service.hasAnyPermission(1L, null)).isFalse();
        assertThat(service.hasAnyPermission(1L, List.of())).isFalse();
    }

    @Test
    void batchResolutionReturnsOneEntryPerRequestedUser() {
        when(permissionSnapshotCache.getEffectivePermissionIds(100L, 1L, 5L))
                .thenReturn(Set.of(50L));
        when(permissionSnapshotCache.getEffectivePermissionIds(100L, 2L, 5L))
                .thenReturn(Set.of(60L));

        Map<Long, Set<Long>> result = service.batchGetUserPermissionIds(List.of(1L, 2L));

        assertThat(result.get(1L)).containsExactly(50L);
        assertThat(result.get(2L)).containsExactly(60L);
        assertThat(service.batchGetUserPermissionIds(List.of())).isEmpty();
    }

    @Test
    void evictionsCarryExplicitTenantKeys() {
        service.evictUserPermissions(100L, 1L);
        service.evictRoleUsers(100L, 7L);
        service.evictPermissionDefinitions(100L);

        verify(permissionSnapshotCache).evictUser(100L, 1L);
        verify(permissionSnapshotCache).evictRole(100L, 7L);
        verify(permissionSnapshotCache).evictPermissionCatalog(100L);
    }

    @Test
    void legacyContextEvictionsAndCatalogClearRemainSupported() {
        service.evictUserPermissions(1L);
        service.evictRoleUsers(7L);
        service.clearPermissionCodeCache();

        verify(permissionSnapshotCache).evictUser(100L, 1L);
        verify(permissionSnapshotCache).evictRole(100L, 7L);
        verify(permissionSnapshotCache).clearPermissionCatalogs();
    }
}
