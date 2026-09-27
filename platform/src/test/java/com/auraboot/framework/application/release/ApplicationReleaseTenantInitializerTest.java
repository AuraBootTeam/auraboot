package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationReleaseTenantInitializerTest {
    private final ApplicationRuntimeDefinitionCatalog catalog = mock(ApplicationRuntimeDefinitionCatalog.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final RoleService roleService = mock(RoleService.class);
    private final ApplicationReleaseTenantInitializer initializer =
            new ApplicationReleaseTenantInitializer(catalog, roleMapper, roleService, new ObjectMapper());

    @Test
    void projectsOnlyTenantOwnedRoleState() {
        when(catalog.roles(42L, "aura-edu")).thenReturn(List.of(RoleDefinitionDTO.builder()
                .code("xy_school_admin").nameZhCN("学校管理员")
                .permissions(List.of("xy.school.manage")).scopeType("tenant").build()));

        initializer.initialize(42L, "aura-edu");

        ArgumentCaptor<Role> role = ArgumentCaptor.forClass(Role.class);
        verify(roleService).createRole(role.capture());
        assertThat(role.getValue().getTenantId()).isEqualTo(42L);
        assertThat(role.getValue().getCode()).isEqualTo("xy_school_admin");
        assertThat(role.getValue().getName()).isEqualTo("学校管理员");
        assertThat(role.getValue().getScopeType()).isEqualTo("tenant");
    }

    @Test
    void isIdempotentForAnExistingTenantRole() {
        when(catalog.roles(42L, "aura-edu")).thenReturn(List.of(
                RoleDefinitionDTO.builder().code("xy_school_admin").build()));
        when(roleMapper.findByTenantIdAndCode(42L, "xy_school_admin")).thenReturn(new Role());

        initializer.initialize(42L, "aura-edu");

        verify(roleService, never()).createRole(org.mockito.ArgumentMatchers.any());
    }
}
