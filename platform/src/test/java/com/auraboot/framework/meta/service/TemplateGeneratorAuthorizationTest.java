package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;
import com.auraboot.framework.meta.template.service.impl.TemplateGeneratorServiceImpl;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Service / hermetic / blocking-commit / unit: reject optional writes before generation. */
@ExtendWith(MockitoExtension.class)
class TemplateGeneratorAuthorizationTest {
    @Mock MetaModelService models;
    @Mock MetaModelMapper modelMapper;
    @Mock PageSchemaService pages;
    @Mock MenuService menus;
    @Mock PermissionService permissions;
    @Mock RolePermissionService rolePermissions;
    @Mock UserPermissionService userPermissions;
    @InjectMocks TemplateGeneratorServiceImpl generator;

    @BeforeEach void context() { MetaContext.setContext(7L, 99L, "actor", "tester"); }
    @AfterEach void clear() { MetaContext.clear(); }

    private CrudTemplateConfig pageOnly() {
        var config = new CrudTemplateConfig();
        config.setCreateMenu(false);
        config.setCreatePermissions(false);
        config.setAssignRoles(false);
        config.setMenuName("Example");
        return config;
    }

    private void deniedBeforeGeneration(CrudTemplateConfig config) {
        assertThatThrownBy(() -> generator.generateCrudPages("example", config))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(models, modelMapper, pages, menus, permissions, rolePermissions);
    }

    @Test void creatingMenusNeedsIndependentMenuAuthority() {
        var config = pageOnly();
        config.setCreateMenu(true);
        deniedBeforeGeneration(config);
        verify(userPermissions).hasPermission(99L, MetaPermission.MENU_MANAGE);
    }

    @Test void creatingPermissionsNeedsIndependentPermissionAuthority() {
        var config = pageOnly();
        config.setCreatePermissions(true);
        deniedBeforeGeneration(config);
        verify(userPermissions).hasPermission(99L, MetaPermission.PERMISSION_MANAGE);
    }

    @Test void assigningRolesNeedsIndependentRoleAuthorityEvenWithoutGeneratedPermissions() {
        var config = pageOnly();
        config.setAssignRoles(true);
        deniedBeforeGeneration(config);
        verify(userPermissions).hasPermission(99L, MetaPermission.ROLE_MANAGE);
    }

    @Test void pageOnlyDoesNotRequireUnrelatedManagementPermissions() {
        assertThatThrownBy(() -> generator.generateCrudPages("example", pageOnly()))
            .isInstanceOf(BusinessException.class);
        verify(models).findByCode("example");
        verifyNoInteractions(userPermissions, pages, menus, permissions, rolePermissions);
    }

    @Test void grantedMenuAuthorityCannotAuthorizePermissionCreation() {
        var config = pageOnly();
        config.setCreateMenu(true);
        config.setCreatePermissions(true);
        when(userPermissions.hasPermission(99L, MetaPermission.MENU_MANAGE)).thenReturn(true);
        deniedBeforeGeneration(config);
        verify(userPermissions).hasPermission(99L, MetaPermission.PERMISSION_MANAGE);
    }
}
