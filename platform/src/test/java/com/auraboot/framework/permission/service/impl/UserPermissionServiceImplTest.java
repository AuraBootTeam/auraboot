package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private PermissionSnapshotCache permissionSnapshotCache;

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
