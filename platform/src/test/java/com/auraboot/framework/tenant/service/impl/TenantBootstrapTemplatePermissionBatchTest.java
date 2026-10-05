package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.tenant.dto.bootstrap.PermissionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class TenantBootstrapTemplatePermissionBatchTest {
    private final PermissionMapper mapper = mock(PermissionMapper.class);
    private final TenantBootstrapServiceImpl service = new TenantBootstrapServiceImpl(
            null, null, null, null, null, null, null, null, null, mapper, null, null, null, null);

    private PermissionTemplate template(String code) {
        var template = new PermissionTemplate();
        template.setCode(code);
        template.setName("Template permission");
        template.setResourceType("model");
        template.setResource("student");
        template.setAction("read");
        template.setModule("education");
        return template;
    }

    private List<Permission> create(List<PermissionTemplate> templates) {
        return ReflectionTestUtils.invokeMethod(service, "createTemplatePermissions", 123L, templates, 456L);
    }

    @Test
    void newPermissionsUseOneTenantReadAndOneBatchWithGeneratedIds() {
        when(mapper.findResolvableDefinitions(123L)).thenReturn(List.of());
        var ids = new AtomicLong(1000L);
        when(mapper.batchInsert(anyList())).thenAnswer(invocation -> {
            List<Permission> permissions = invocation.getArgument(0);
            permissions.forEach(permission -> permission.setId(ids.getAndIncrement()));
            return permissions.size();
        });
        var result = create(List.of(template("model.student.read"), template("model.student.update")));
        verify(mapper).findResolvableDefinitions(123L);
        verify(mapper).batchInsert(anyList());
        verify(mapper, never()).findByTenantIdAndCode(any(), any());
        verify(mapper, never()).insert(any(Permission.class));
        assertThat(result).hasSize(2).allSatisfy(permission -> {
            assertThat(permission.getId()).isNotNull();
            assertThat(permission.getTenantId()).isEqualTo(123L);
            assertThat(permission.getCreatedBy()).isEqualTo(456L);
            assertThat(permission.getUpdatedBy()).isEqualTo(456L);
            assertThat(permission.getDeletedFlag()).isFalse();
            assertThat(permission.getStatus()).isEqualTo("active");
            assertThat(permission.getSourceRef()).isEqualTo("education");
        });
        when(mapper.findResolvableDefinitions(123L)).thenReturn(result);
        clearInvocations(mapper);
        assertThat(create(List.of(template("model.student.read"), template("model.student.update"))))
                .containsExactlyElementsOf(result);
        verify(mapper).findResolvableDefinitions(123L);
        verify(mapper, never()).batchInsert(anyList());
        verify(mapper, never()).insert(any(Permission.class));
    }

    @Test
    void newestCaseInsensitivePermissionIsReusedIncludingRepeatedTemplateCodes() {
        var newest = new Permission();
        newest.setId(42L); newest.setTenantId(123L); newest.setCode("MODEL.STUDENT.READ");
        var older = new Permission();
        older.setId(41L); older.setTenantId(123L); older.setCode("model.student.read");
        when(mapper.findResolvableDefinitions(123L)).thenReturn(List.of(newest, older));
        assertThat(create(List.of(template("model.student.read"), template("MODEL.STUDENT.READ"))))
                .containsExactly(newest, newest);
        verify(mapper).findResolvableDefinitions(123L);
        verify(mapper, never()).findByTenantIdAndCode(any(), any());
        verify(mapper, never()).batchInsert(anyList());
        verify(mapper, never()).insert(any(Permission.class));
    }

    @Test
    void missingGeneratedIdentityCannotBeBoundAsAnIncompletePermission() {
        when(mapper.findResolvableDefinitions(123L)).thenReturn(List.of());
        when(mapper.batchInsert(anyList())).thenReturn(1);
        assertThatThrownBy(() -> create(List.of(template("model.student.read"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("every generated identity");
    }
}
