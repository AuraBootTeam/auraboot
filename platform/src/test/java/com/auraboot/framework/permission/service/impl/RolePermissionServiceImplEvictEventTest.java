package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.event.RolePermissionChangedEvent;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Role-permission mutations must publish {@link RolePermissionChangedEvent} so
 * {@code PermissionCacheEvictionListener} evicts user permission caches AFTER COMMIT.
 * Regression guard for the "grant/revoke takes up to 30min (cache TTL)" defect
 * (DDR-2026-06-29 §12): revoked permissions kept working until the Caffeine entry expired.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RolePermissionServiceImpl cache-eviction events")
class RolePermissionServiceImplEvictEventTest {

    @Mock private RolePermissionMapper rolePermissionMapper;
    @Mock private PermissionMapper permissionMapper;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private RoleMapper roleMapper;
    @Mock private DataScopeService dataScopeService;

    private RolePermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RolePermissionServiceImpl(
            rolePermissionMapper, permissionMapper, eventPublisher, roleMapper, dataScopeService);
        MetaContext.setCurrentTenantId(1L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("assignPermissionsToRole publishes RolePermissionChangedEvent for the role")
    void assignPublishesEvent() {
        when(rolePermissionMapper.batchInsert(anyList())).thenReturn(1);

        assertTrue(service.assignPermissionsToRole(42L, List.of(7L)));

        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
            ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        RolePermissionChangedEvent event = (RolePermissionChangedEvent) captor.getValue();
        assertEquals(42L, event.getRoleId());
    }

    @Test
    @DisplayName("removePermission publishes RolePermissionChangedEvent (revoke must evict)")
    void removePublishesEvent() {
        when(rolePermissionMapper.deleteByRoleAndPermission(anyLong(), anyLong(), any()))
            .thenReturn(1);

        assertTrue(service.removePermission(42L, 7L));

        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
            ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        RolePermissionChangedEvent event = (RolePermissionChangedEvent) captor.getValue();
        assertEquals(42L, event.getRoleId());
    }
}
