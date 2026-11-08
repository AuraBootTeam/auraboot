package com.auraboot.framework.permission.listener;

import com.auraboot.framework.meta.cache.DataAccessCacheEpoch;
import com.auraboot.framework.permission.event.PermissionDefinitionChangedEvent;
import com.auraboot.framework.permission.event.RolePermissionChangedEvent;
import com.auraboot.framework.permission.event.UserRoleChangedEvent;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that transaction listeners preserve explicit tenant isolation during eviction. */
@ExtendWith(MockitoExtension.class)
class PermissionCacheEvictionListenerTest {

    @Mock
    private UserPermissionService userPermissionService;

    @Mock
    private org.springframework.cache.CacheManager cacheManager;

    @Mock
    private org.springframework.cache.Cache cache;

    private PermissionSnapshotCache snapshotCache;

    private PermissionCacheEvictionListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(cacheManager.getCache(anyString())).thenReturn(cache);
        snapshotCache = new PermissionSnapshotCache(
                null, null, null, null, null, cacheManager);
        listener = new PermissionCacheEvictionListener(userPermissionService, snapshotCache);
    }

    @Test
    void userRoleEventEvictsOnlyTheTenantUserSnapshot() {
        listener.onUserRoleChanged(new UserRoleChangedEvent(
                this, 100L, 1L, 7L, "CREATE"));

        verify(userPermissionService).evictUserPermissions(100L, 1L);
    }

    @Test
    void rolePermissionEventEvictsTenantRoleAndDerivedSnapshots() {
        listener.onRolePermissionChanged(new RolePermissionChangedEvent(
                this, 100L, 7L, 50L, "DELETE"));

        verify(userPermissionService).evictRoleUsers(100L, 7L);
    }

    @Test
    void definitionEventEvictsTenantCatalogIncludingNegativeEntries() {
        listener.onPermissionDefinitionChanged(new PermissionDefinitionChangedEvent(
                this, 100L, "model.user.read", "CREATE"));

        verify(userPermissionService).evictPermissionDefinitions(100L);
    }

    @Test
    void legacyEventsFallBackToCurrentContextMethods() {
        listener.onUserRoleChanged(new UserRoleChangedEvent(this, 1L, 7L, "DELETE"));
        listener.onRolePermissionChanged(new RolePermissionChangedEvent(this, 7L, 50L, "UPDATE"));

        verify(userPermissionService).evictUserPermissions(1L);
        verify(userPermissionService).evictRoleUsers(7L);
    }

    @Test
    void permissionEventsBumpTheDataAccessCacheEpoch() {
        long before = DataAccessCacheEpoch.current();

        listener.onUserRoleChanged(new UserRoleChangedEvent(this, 100L, 1L, 7L, "CREATE"));
        listener.onRolePermissionChanged(new RolePermissionChangedEvent(this, 100L, 7L, 50L, "DELETE"));
        listener.onPermissionDefinitionChanged(new PermissionDefinitionChangedEvent(
                this, 100L, "model.user.read", "CREATE"));

        assertThat(DataAccessCacheEpoch.current()).isGreaterThan(before + 1);
    }

    @Test
    void pluginImportCompletedClearsEveryPermissionRegionAndOrphansEpochKeys() {
        long before = DataAccessCacheEpoch.current();

        listener.onPluginImportCompleted(new PluginImportCompletedEvent(this, 100L, "edu-core"));

        verify(cacheManager, org.mockito.Mockito.atLeastOnce()).getCache(org.mockito.ArgumentMatchers.anyString());
        assertThat(DataAccessCacheEpoch.current()).isGreaterThan(before);
    }
}
