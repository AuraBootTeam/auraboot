package com.auraboot.framework.permission.listener;

import com.auraboot.framework.permission.event.PermissionDefinitionChangedEvent;
import com.auraboot.framework.permission.event.RolePermissionChangedEvent;
import com.auraboot.framework.permission.event.UserRoleChangedEvent;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/** Verifies that transaction listeners preserve explicit tenant isolation during eviction. */
@ExtendWith(MockitoExtension.class)
class PermissionCacheEvictionListenerTest {

    @Mock
    private UserPermissionService userPermissionService;

    private PermissionCacheEvictionListener listener;

    @BeforeEach
    void setUp() {
        listener = new PermissionCacheEvictionListener(userPermissionService);
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
}
