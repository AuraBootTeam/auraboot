package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit coverage for cache keys, negative entries, invalidation, and concurrent miss collapse. */
@ExtendWith(MockitoExtension.class)
class PermissionSnapshotCacheTest {

    @Mock
    private PermissionMapper permissionMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private RolePermissionMapper rolePermissionMapper;
    @Mock
    private RoleMapper roleMapper;

    private PermissionSnapshotCache cache;

    @BeforeEach
    void setUp() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                PermissionSnapshotCache.PERMISSION_CATALOG_CACHE,
                PermissionSnapshotCache.USER_ROLE_CACHE,
                PermissionSnapshotCache.ROLE_PERMISSION_CACHE,
                PermissionSnapshotCache.BASELINE_ROLE_CACHE,
                PermissionSnapshotCache.EFFECTIVE_PERMISSION_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterWrite(Duration.ofMinutes(5)));
        manager.setAllowNullValues(false);
        cache = new PermissionSnapshotCache(
                permissionMapper, userRoleMapper, rolePermissionMapper, roleMapper, manager);
    }

    @Test
    void permissionCatalogLoadsOnceAndIncludesNegativeLookups() {
        Permission permission = permission(50L, "Model.User.Read");
        when(permissionMapper.findResolvableDefinitions()).thenReturn(List.of(permission));

        assertThat(cache.resolvePermissionId(100L, "model.user.read")).isEqualTo(50L);
        assertThat(cache.resolvePermissionId(100L, "MODEL.USER.READ")).isEqualTo(50L);
        assertThat(cache.resolvePermissionId(100L, "missing.code")).isNull();
        assertThat(cache.resolvePermissionId(100L, "missing.code")).isNull();

        verify(permissionMapper, times(1)).findResolvableDefinitions();
    }

    @Test
    void permissionCodesPreserveCanonicalDatabaseCase() {
        Permission permission = permission(50L, "Model.User.Read");
        when(permissionMapper.findResolvableDefinitions()).thenReturn(List.of(permission));

        assertThat(cache.resolvePermissionCodes(100L, Set.of(50L)))
                .containsExactly("Model.User.Read");
    }

    @Test
    void permissionCatalogPreservesTheCompleteDefinitionForPolicyEvaluation() {
        Permission permission = permission(50L, "model.user.read");
        permission.setPolicySchema(Map.of("maxRows", Map.of("type", "number")));
        when(permissionMapper.findResolvableDefinitions()).thenReturn(List.of(permission));

        Permission resolved = cache.resolvePermissionDefinition(100L, "MODEL.USER.READ");

        assertThat(resolved).isSameAs(permission);
        assertThat(resolved.getPolicySchema()).isEqualTo(permission.getPolicySchema());
        verify(permissionMapper, times(1)).findResolvableDefinitions();
    }

    @Test
    void permissionCatalogKeysAreTenantIsolated() {
        Permission tenantOnePermission = permission(50L, "tenant.one.read");
        Permission tenantTwoPermission = permission(60L, "tenant.two.read");
        when(permissionMapper.findResolvableDefinitions())
                .thenReturn(List.of(tenantOnePermission))
                .thenReturn(List.of(tenantTwoPermission));

        assertThat(cache.resolvePermissionId(100L, "tenant.one.read")).isEqualTo(50L);
        assertThat(cache.resolvePermissionId(100L, "tenant.two.read")).isNull();
        assertThat(cache.resolvePermissionId(200L, "tenant.two.read")).isEqualTo(60L);
        assertThat(cache.resolvePermissionId(200L, "tenant.one.read")).isNull();

        verify(permissionMapper, times(2)).findResolvableDefinitions();
    }

    @Test
    void permissionCatalogEvictionRefreshesPreviouslyMissingCode() {
        Permission permission = permission(50L, "new.permission");
        when(permissionMapper.findResolvableDefinitions())
                .thenReturn(List.of())
                .thenReturn(List.of(permission));

        assertThat(cache.resolvePermissionId(100L, "new.permission")).isNull();
        cache.evictPermissionCatalog(100L);
        assertThat(cache.resolvePermissionId(100L, "new.permission")).isEqualTo(50L);

        verify(permissionMapper, times(2)).findResolvableDefinitions();
    }

    @Test
    void repeatedEffectiveResolutionLoadsEveryDatabaseLayerOnce() {
        stubUserRole(100L, 5L, 7L);
        stubBaseline(100L, 99L);
        when(rolePermissionMapper.findPermissionIdsByRole(7L)).thenReturn(Set.of(50L));
        when(rolePermissionMapper.findPermissionIdsByRole(99L)).thenReturn(Set.of(60L));

        assertThat(cache.getEffectivePermissionIds(100L, 1L, 5L))
                .containsExactlyInAnyOrder(50L, 60L);
        assertThat(cache.getEffectivePermissionIds(100L, 1L, 5L))
                .containsExactlyInAnyOrder(50L, 60L);

        verify(userRoleMapper, times(1)).findByMemberIdAndTenantId(5L, 100L);
        verify(roleMapper, times(1)).findByTenantIdAndCode(100L, "tenant_member");
        verify(rolePermissionMapper, times(1)).findPermissionIdsByRole(7L);
        verify(rolePermissionMapper, times(1)).findPermissionIdsByRole(99L);
    }

    @Test
    void emptyRoleAndPermissionResultsAreCachedFailClosed() {
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of());
        when(roleMapper.findByTenantIdAndCode(100L, "tenant_member")).thenReturn(null);

        assertThat(cache.getEffectivePermissionIds(100L, 1L, 5L)).isEmpty();
        assertThat(cache.getEffectivePermissionIds(100L, 1L, 5L)).isEmpty();

        verify(userRoleMapper, times(1)).findByMemberIdAndTenantId(5L, 100L);
        verify(roleMapper, times(1)).findByTenantIdAndCode(100L, "tenant_member");
    }

    @Test
    void tenantAndUserKeysPreventCrossTenantSnapshotReuse() {
        stubUserRole(100L, 5L, 7L);
        stubUserRole(200L, 6L, 8L);
        when(roleMapper.findByTenantIdAndCode(100L, "tenant_member")).thenReturn(null);
        when(roleMapper.findByTenantIdAndCode(200L, "tenant_member")).thenReturn(null);
        when(rolePermissionMapper.findPermissionIdsByRole(7L)).thenReturn(Set.of(50L));
        when(rolePermissionMapper.findPermissionIdsByRole(8L)).thenReturn(Set.of(60L));

        assertThat(cache.getEffectivePermissionIds(100L, 1L, 5L)).containsExactly(50L);
        assertThat(cache.getEffectivePermissionIds(200L, 1L, 6L)).containsExactly(60L);

        verify(userRoleMapper).findByMemberIdAndTenantId(5L, 100L);
        verify(userRoleMapper).findByMemberIdAndTenantId(6L, 200L);
    }

    @Test
    void usersSharingRoleReuseRoleGrantSnapshot() {
        stubUserRole(100L, 5L, 7L);
        stubUserRole(100L, 6L, 7L);
        when(roleMapper.findByTenantIdAndCode(100L, "tenant_member")).thenReturn(null);
        when(rolePermissionMapper.findPermissionIdsByRole(7L)).thenReturn(Set.of(50L));

        assertThat(cache.getEffectivePermissionIds(100L, 1L, 5L)).containsExactly(50L);
        assertThat(cache.getEffectivePermissionIds(100L, 2L, 6L)).containsExactly(50L);

        verify(rolePermissionMapper, times(1)).findPermissionIdsByRole(7L);
        verify(roleMapper, times(1)).findByTenantIdAndCode(100L, "tenant_member");
    }

    @Test
    void userEvictionReloadsMembershipButReusesUnchangedRoleGrant() {
        stubUserRole(100L, 5L, 7L);
        when(roleMapper.findByTenantIdAndCode(100L, "tenant_member")).thenReturn(null);
        when(rolePermissionMapper.findPermissionIdsByRole(7L)).thenReturn(Set.of(50L));

        cache.getEffectivePermissionIds(100L, 1L, 5L);
        cache.evictUser(100L, 1L);
        cache.getEffectivePermissionIds(100L, 1L, 5L);

        verify(userRoleMapper, times(2)).findByMemberIdAndTenantId(5L, 100L);
        verify(rolePermissionMapper, times(1)).findPermissionIdsByRole(7L);
    }

    @Test
    void roleEvictionReloadsGrantAndDerivedEffectiveSnapshot() {
        stubUserRole(100L, 5L, 7L);
        when(roleMapper.findByTenantIdAndCode(100L, "tenant_member")).thenReturn(null);
        when(rolePermissionMapper.findPermissionIdsByRole(7L))
                .thenReturn(Set.of(50L))
                .thenReturn(Set.of(51L));

        assertThat(cache.getEffectivePermissionIds(100L, 1L, 5L)).containsExactly(50L);
        cache.evictRole(100L, 7L);
        assertThat(cache.getEffectivePermissionIds(100L, 1L, 5L)).containsExactly(51L);

        verify(userRoleMapper, times(1)).findByMemberIdAndTenantId(5L, 100L);
        verify(rolePermissionMapper, times(2)).findPermissionIdsByRole(7L);
    }

    @Test
    void concurrentMissesCollapseIntoSingleDatabaseLoad() throws Exception {
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        UserRole userRole = new UserRole();
        userRole.setRoleId(7L);
        when(userRoleMapper.findByMemberIdAndTenantId(5L, 100L)).thenAnswer(invocation -> {
            loaderEntered.countDown();
            releaseLoader.await(5, TimeUnit.SECONDS);
            return List.of(userRole);
        });
        when(roleMapper.findByTenantIdAndCode(100L, "tenant_member")).thenReturn(null);
        when(rolePermissionMapper.findPermissionIdsByRole(7L)).thenReturn(Set.of(50L));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Set<Long>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() ->
                        cache.getEffectivePermissionIds(100L, 1L, 5L)));
            }
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseLoader.countDown();
            for (Future<Set<Long>> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).containsExactly(50L);
            }
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }

        verify(userRoleMapper, times(1)).findByMemberIdAndTenantId(5L, 100L);
        verify(rolePermissionMapper, times(1)).findPermissionIdsByRole(7L);
    }

    @Test
    void returnedSnapshotsAreImmutable() {
        stubUserRole(100L, 5L, 7L);
        when(roleMapper.findByTenantIdAndCode(100L, "tenant_member")).thenReturn(null);
        when(rolePermissionMapper.findPermissionIdsByRole(7L)).thenReturn(Set.of(50L));

        Set<Long> result = cache.getEffectivePermissionIds(100L, 1L, 5L);

        assertThatThrownBy(() -> result.add(99L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private void stubUserRole(Long tenantId, Long memberId, Long roleId) {
        UserRole userRole = new UserRole();
        userRole.setRoleId(roleId);
        when(userRoleMapper.findByMemberIdAndTenantId(memberId, tenantId))
                .thenReturn(List.of(userRole));
    }

    private void stubBaseline(Long tenantId, Long roleId) {
        Role baseline = new Role();
        baseline.setId(roleId);
        when(roleMapper.findByTenantIdAndCode(tenantId, "tenant_member"))
                .thenReturn(baseline);
    }

    private Permission permission(Long id, String code) {
        Permission permission = new Permission();
        permission.setId(id);
        permission.setCode(code);
        return permission;
    }
}
