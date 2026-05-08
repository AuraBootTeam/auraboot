package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.i18n.service.I18nResourceService;
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
import com.auraboot.framework.tenant.exception.TemplateParseException;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantBootstrapServiceImpl")
class TenantBootstrapServiceImplTest {

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
                dynamicDataMapper);
    }

    private RoleTemplate roleTpl(String code, String name, int priority) {
        RoleTemplate r = new RoleTemplate();
        r.setCode(code);
        r.setName(name);
        r.setPriority(priority);
        return r;
    }

    private MenuTemplate menuTpl(String code, String name, int type, String path) {
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
    @DisplayName("validateTemplate accepts a complete minimal template")
    void validateOk() {
        service.validateTemplate(baseTemplate());
    }

    @Test
    @DisplayName("validateTemplate rejects blank name")
    void validateBlankName() {
        TenantBootstrapTemplate t = baseTemplate();
        t.setName("  ");
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects blank version")
    void validateBlankVersion() {
        TenantBootstrapTemplate t = baseTemplate();
        t.setVersion(null);
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate requires at least one role")
    void validateNoRoles() {
        TenantBootstrapTemplate t = baseTemplate();
        t.setRoles(List.of());
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects role with blank code")
    void validateRoleBlankCode() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getRoles().get(0).setCode("");
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects role with blank name")
    void validateRoleBlankName() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getRoles().get(0).setName(null);
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects role with negative priority")
    void validateRoleNegativePriority() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getRoles().get(0).setPriority(-1);
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects menu with blank code")
    void validateMenuBlankCode() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getMenus().add(menuTpl("", "Home", 1, "/home"));
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects leaf menu without path")
    void validateMenuLeafNoPath() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getMenus().add(menuTpl("home", "Home", 1, ""));
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects menu with invalid type")
    void validateMenuInvalidType() {
        TenantBootstrapTemplate t = baseTemplate();
        t.getMenus().add(menuTpl("home", "Home", 9, "/home"));
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects menu referring undefined permissionCode")
    void validateMenuUnknownPermission() {
        TenantBootstrapTemplate t = baseTemplate();
        MenuTemplate m = menuTpl("home", "Home", 1, "/home");
        m.setPermissionCode("not.defined");
        t.getMenus().add(m);
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate accepts menu when permissionCode defined")
    void validateMenuPermissionDefined() {
        TenantBootstrapTemplate t = baseTemplate();
        MenuTemplate m = menuTpl("home", "Home", 1, "/home");
        m.setPermissionCode("home.read");
        t.getMenus().add(m);
        PermissionTemplate p = new PermissionTemplate();
        p.setCode("home.read");
        t.setPermissions(List.of(p));
        service.validateTemplate(t);
    }

    @Test
    @DisplayName("validateTemplate rejects permission template with blank code")
    void validatePermissionBlankCode() {
        TenantBootstrapTemplate t = baseTemplate();
        PermissionTemplate p = new PermissionTemplate();
        p.setCode("");
        t.setPermissions(List.of(p));
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("validateTemplate rejects empty role-permission bindings")
    void validateEmptyBindings() {
        TenantBootstrapTemplate t = baseTemplate();
        t.setRolePermissionBindings(List.of());
        assertThrows(TemplateValidationException.class, () -> service.validateTemplate(t));
    }

    @Test
    @DisplayName("loadTemplate throws when resource missing")
    void loadTemplateMissing() {
        Resource res = new ByteArrayResource(new byte[0]) {
            @Override public boolean exists() { return false; }
        };
        when(resourceLoader.getResource("classpath:tenant-templates/missing.json")).thenReturn(res);

        assertThrows(TemplateNotFoundException.class, () -> service.loadTemplate("missing"));
    }

    @Test
    @DisplayName("loadTemplate parses JSON into template")
    void loadTemplateOk() throws IOException {
        String json = "{\"name\":\"ok\",\"version\":\"1.0\",\"roles\":[],\"menus\":[]}";
        Resource res = new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
        when(resourceLoader.getResource("classpath:tenant-templates/ok.json")).thenReturn(res);

        TenantBootstrapTemplate t = service.loadTemplate("ok");
        assertEquals("ok", t.getName());
        assertEquals("1.0", t.getVersion());
    }

    @Test
    @DisplayName("loadTemplate wraps IO errors in TemplateParseException")
    void loadTemplateParseError() {
        Resource broken = new Resource() {
            @Override public boolean exists() { return true; }
            @Override public boolean isReadable() { return true; }
            @Override public boolean isOpen() { return false; }
            @Override public java.net.URL getURL() throws IOException { throw new IOException(); }
            @Override public java.net.URI getURI() throws IOException { throw new IOException(); }
            @Override public java.io.File getFile() throws IOException { throw new IOException(); }
            @Override public long contentLength() { return 0; }
            @Override public long lastModified() { return 0; }
            @Override public Resource createRelative(String relativePath) { return null; }
            @Override public String getFilename() { return "x"; }
            @Override public String getDescription() { return "x"; }
            @Override public InputStream getInputStream() throws IOException {
                throw new IOException("boom");
            }
        };
        when(resourceLoader.getResource("classpath:tenant-templates/broken.json")).thenReturn(broken);

        assertThrows(TemplateParseException.class, () -> service.loadTemplate("broken"));
    }
}
