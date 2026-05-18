package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.permission.service.SystemPermissionInitializer;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dto.bootstrap.MenuTemplate;
import com.auraboot.framework.tenant.dto.bootstrap.PermissionTemplate;
import com.auraboot.framework.tenant.dto.bootstrap.RolePermissionBinding;
import com.auraboot.framework.tenant.dto.bootstrap.RoleTemplate;
import com.auraboot.framework.tenant.dto.bootstrap.TenantBootstrapTemplate;
import com.auraboot.framework.tenant.exception.TemplateNotFoundException;
import com.auraboot.framework.tenant.exception.TemplateValidationException;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantBootstrapServiceImpl branch coverage")
class TenantBootstrapServiceImplBranchTest {

    @Mock private ResourceLoader resourceLoader;
    @Mock private RoleService roleService;
    @Mock private MenuService menuService;
    @Mock private RolePermissionService rolePermissionService;
    @Mock private AutoPermissionAssignmentService autoPermissionAssignmentService;
    @Mock private UserRoleService userRoleService;
    @Mock private TenantMemberService tenantMemberService;
    @Mock private SystemPermissionInitializer systemPermissionInitializer;
    @Mock private PermissionMapper permissionMapper;
    @Mock private I18nResourceService i18nResourceService;
    @Mock private I18nService i18nService;
    @Mock private DynamicDataMapper dynamicDataMapper;

    private TenantBootstrapServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TenantBootstrapServiceImpl(
                resourceLoader,
                new ObjectMapper(),
                roleService,
                menuService,
                rolePermissionService,
                autoPermissionAssignmentService,
                userRoleService,
                tenantMemberService,
                systemPermissionInitializer,
                permissionMapper,
                i18nResourceService,
                i18nService,
                dynamicDataMapper);
    }

    private RoleTemplate roleTpl(String code, String name, Integer priority) {
        RoleTemplate r = new RoleTemplate();
        r.setCode(code);
        r.setName(name);
        r.setPriority(priority);
        return r;
    }

    private MenuTemplate menuTpl(String code, String name, Integer type, String path) {
        MenuTemplate m = new MenuTemplate();
        m.setCode(code);
        m.setName(name);
        m.setType(type);
        m.setPath(path);
        return m;
    }

    private TenantBootstrapTemplate baseTemplate() {
        TenantBootstrapTemplate t = new TenantBootstrapTemplate();
        t.setName("default");
        t.setVersion("1.0");
        t.setRoles(new ArrayList<>(List.of(roleTpl("admin", "Admin", 10))));
        t.setMenus(new ArrayList<>());
        RolePermissionBinding b = new RolePermissionBinding();
        b.setRoleCode("admin");
        b.setPermissionCodes(List.of("*"));
        t.setRolePermissionBindings(List.of(b));
        return t;
    }

    @Test
    @DisplayName("validateTemplate rejects null role-permission bindings")
    void validateNullBindings() {
        TenantBootstrapTemplate t = baseTemplate();
        t.setRolePermissionBindings(null);
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects role with whitespace-only code")
    void validateRoleWhitespaceCode() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getRoles().get(0).setCode("   ");
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects role with whitespace-only name")
    void validateRoleWhitespaceName() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getRoles().get(0).setName("   ");
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects role with null priority")
    void validateRoleNullPriority() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getRoles().get(0).setPriority(null);
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects menu with whitespace code")
    void validateMenuWhitespaceCode() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getMenus().add(menuTpl("   ", "Home", 1, "/home"));
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects menu with blank name")
    void validateMenuBlankName() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getMenus().add(menuTpl("home", "", 1, "/home"));
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects menu with null type")
    void validateMenuNullType() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getMenus().add(menuTpl("home", "Home", null, "/home"));
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate accepts directory menu (type=0) without path")
    void validateMenuDirectoryNoPath() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getMenus().add(menuTpl("group", "Group", 0, null));
        assertDoesNotThrow(() -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects permission template with whitespace code")
    void validatePermissionWhitespaceCode() {
        TenantBootstrapTemplate t = baseTemplate();
        PermissionTemplate p = new PermissionTemplate();
        p.setCode("   ");
        t.setPermissions(List.of(p));
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate accepts when permissions list null and no menu permission ref")
    void validateNullPermissionsOk() {
        TenantBootstrapTemplate t = baseTemplate();
        t.setPermissions(null);
        assertDoesNotThrow(() -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate accepts menu with blank permissionCode (treated as no permission)")
    void validateMenuBlankPermissionCode() {
        TenantBootstrapTemplate t = baseTemplate();
        MenuTemplate m = menuTpl("home", "Home", 1, "/home");
        m.setPermissionCode("   ");
        t.getMenus().add(m);
        assertDoesNotThrow(() -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("loadTemplate throws TemplateNotFoundException via empty resource not exists")
    void loadTemplateNotFound() {
        Resource res = new ByteArrayResource(new byte[0]) {
            @Override public boolean exists() { return false; }
        };
        when(resourceLoader.getResource("classpath:tenant-templates/none.json")).thenReturn(res);
        assertThrows(TemplateNotFoundException.class, () -> service.loadTemplate("none"));
    }

    @Test
    @DisplayName("validateTemplate rejects null name")
    void validateNullName() {
        TenantBootstrapTemplate t = baseTemplate();
        t.setName(null);
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects whitespace version")
    void validateWhitespaceVersion() {
        TenantBootstrapTemplate t = baseTemplate();
        t.setVersion("   ");
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects null roles list")
    void validateNullRoles() {
        TenantBootstrapTemplate t = baseTemplate();
        t.setRoles(null);
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }
}
