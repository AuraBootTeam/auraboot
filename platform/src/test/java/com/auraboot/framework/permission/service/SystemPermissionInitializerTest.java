package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemPermissionInitializerTest {

    @Mock
    private PermissionMapper permissionMapper;

    @Test
    void generatedPermissionsAreActiveNotLegacyNullDeletedFlag() {
        when(permissionMapper.findByCode(any())).thenReturn(null);

        AtomicLong nextId = new AtomicLong(1000);
        doAnswer(invocation -> {
            Permission permission = invocation.getArgument(0);
            permission.setId(nextId.getAndIncrement());
            return 1;
        }).when(permissionMapper).insert(any(Permission.class));

        SystemPermissionInitializer initializer = new SystemPermissionInitializer(permissionMapper);
        initializer.initializeSystemPermissions(123L);

        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissionMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Permission::getDeletedFlag)
                .containsOnly(false);
    }
}
