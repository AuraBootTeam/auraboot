package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemPermissionInitializerTest {

    @Mock
    private PermissionMapper permissionMapper;

    @Test
    void generatedPermissionsAreActiveNotLegacyNullDeletedFlag() {
        when(permissionMapper.findResolvableDefinitions(123L)).thenReturn(List.of());

        AtomicLong nextId = new AtomicLong(1000);
        doAnswer(invocation -> {
            Permission permission = invocation.getArgument(0);
            permission.setId(nextId.getAndIncrement());
            return 1;
        }).when(permissionMapper).insert(any(Permission.class));

        SystemPermissionInitializer initializer = new SystemPermissionInitializer(permissionMapper);
        var permissions = initializer.initializeSystemPermissions(123L);
        verify(permissionMapper).findResolvableDefinitions(123L);
        verify(permissionMapper, never()).findByTenantIdAndCode(any(), any());
        Map<Long, Permission> byId = permissions.stream().collect(Collectors.toMap(
                Permission::getId, Function.identity(), (first, repeated) -> first));
        assertThat(permissions).allSatisfy(permission -> {
            assertThat(permission.getTenantId()).isEqualTo(123L);
            if (permission.getLevel() > 1) {
                assertThat(byId.get(permission.getParentId())).isNotNull();
                assertThat(byId.get(permission.getParentId()).getLevel()).isEqualTo(permission.getLevel() - 1);
            }
        });

        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissionMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Permission::getDeletedFlag)
                .containsOnly(false);
    }

    @Test
    void reentryPreservesIdsAndAvoidsIndividualLookupsOrWrites() {
        when(permissionMapper.findResolvableDefinitions(123L)).thenReturn(List.of());
        AtomicLong nextId = new AtomicLong(1000);
        doAnswer(invocation -> {
            Permission permission = invocation.getArgument(0);
            permission.setId(nextId.getAndIncrement());
            return 1;
        }).when(permissionMapper).insert(any(Permission.class));
        var initializer = new SystemPermissionInitializer(permissionMapper);
        var created = initializer.initializeSystemPermissions(123L);
        when(permissionMapper.findResolvableDefinitions(123L)).thenReturn(created);
        clearInvocations(permissionMapper);
        var repeated = initializer.initializeSystemPermissions(123L);
        assertThat(repeated).containsExactlyElementsOf(created);
        verify(permissionMapper).findResolvableDefinitions(123L);
        verify(permissionMapper, never()).findByTenantIdAndCode(any(), any());
        verify(permissionMapper, never()).insert(any(Permission.class));
    }

    @Test
    void partialCatalogUsesNewestCaseInsensitiveParentWithoutChangingItsId() {
        Permission current = new Permission();
        current.setId(42L); current.setTenantId(123L); current.setCode("MODULE.PLATFORM"); current.setLevel(1);
        Permission older = new Permission();
        older.setId(41L); older.setTenantId(123L); older.setCode("module.platform"); older.setLevel(1);
        when(permissionMapper.findResolvableDefinitions(123L)).thenReturn(List.of(current, older));
        AtomicLong nextId = new AtomicLong(1000);
        doAnswer(invocation -> {
            Permission permission = invocation.getArgument(0);
            permission.setId(nextId.getAndIncrement());
            return 1;
        }).when(permissionMapper).insert(any(Permission.class));
        var permissions = new SystemPermissionInitializer(permissionMapper).initializeSystemPermissions(123L);
        assertThat(permissions).contains(current).doesNotContain(older);
        assertThat(permissions.stream().filter(p -> "system.model".equals(p.getCode())).findFirst().orElseThrow().getParentId())
                .isEqualTo(42L);
        assertThat(permissions.stream().filter(p -> "model.sys_user".equals(p.getCode())).findFirst().orElseThrow().getParentId())
                .isEqualTo(42L);
        verify(permissionMapper, never()).findByTenantIdAndCode(any(), any());
    }
}
