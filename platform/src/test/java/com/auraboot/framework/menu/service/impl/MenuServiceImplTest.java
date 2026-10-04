package com.auraboot.framework.menu.service.impl;

import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuEnvironmentScopeService;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MenuServiceImpl
 */
class MenuServiceImplTest {

    private MenuServiceImpl releaseService(com.auraboot.framework.menu.mapper.MenuMapper mapper,
                                           com.auraboot.framework.permission.service.UserPermissionService permissions,
                                           com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog catalog) {
        MenuServiceImpl service = new MenuServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baseMapper", mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "userPermissionService", permissions);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "applicationRuntimeDefinitionCatalog", catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "applicationRuntimePrimaryEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "defaultApplicationCode", "example");
        return service;
    }

    private List<Menu> releaseMenus(boolean visible, ExtensionBean extension) {
        Menu leaf = new Menu(); leaf.setCode("import"); leaf.setPermissionCode("model.import.create");
        leaf.setType(1); leaf.setVisible(visible); leaf.setExtension(extension);
        Menu directory = new Menu(); directory.setCode("classes"); directory.setType(0); directory.setVisible(true);
        directory.setChildren(List.of(leaf));
        return List.of(directory);
    }

    @Test
    void releasePermissionCheckTracksExistingSessionGrantAndRevocationWithoutSyntheticIds() {
        var mapper = org.mockito.Mockito.mock(com.auraboot.framework.menu.mapper.MenuMapper.class);
        var permissions = org.mockito.Mockito.mock(com.auraboot.framework.permission.service.UserPermissionService.class);
        var catalog = org.mockito.Mockito.mock(com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog.class);
        org.mockito.Mockito.when(catalog.menuTree(42L, "example")).thenAnswer(call -> releaseMenus(true, null));
        org.mockito.Mockito.when(permissions.getUserPermissionCodes(7L))
                .thenReturn(java.util.Set.of(), java.util.Set.of("model.import.create"), java.util.Set.of());
        var service = releaseService(mapper, permissions, catalog);
        assertFalse(service.hasMenuPermission(7L, "model.import.create", 42L));
        assertTrue(service.hasMenuPermission(7L, "model.import.create", 42L));
        assertFalse(service.hasMenuPermission(7L, "model.import.create", 42L));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(2)).findByPermissionCode("model.import.create");
    }

    @Test
    void releasePermissionCheckDeniesHiddenUnknownAndWrongEnvironmentMenus() {
        var mapper = org.mockito.Mockito.mock(com.auraboot.framework.menu.mapper.MenuMapper.class);
        var permissions = org.mockito.Mockito.mock(com.auraboot.framework.permission.service.UserPermissionService.class);
        var catalog = org.mockito.Mockito.mock(com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog.class);
        org.mockito.Mockito.when(permissions.getUserPermissionCodes(7L)).thenReturn(java.util.Set.of("model.import.create", "unknown"));
        var service = releaseService(mapper, permissions, catalog);
        org.mockito.Mockito.when(catalog.menuTree(42L, "example")).thenAnswer(call -> releaseMenus(false, null));
        assertFalse(service.hasMenuPermission(7L, "model.import.create", 42L));
        assertFalse(service.hasMenuPermission(7L, "unknown", 42L));
        ExtensionBean extension = new ExtensionBean();
        extension.setDynamicProperty("authoringEnvironmentIds", List.of(12L));
        org.mockito.Mockito.when(catalog.menuTree(42L, "example")).thenAnswer(call -> releaseMenus(true, extension));
        try {
            com.auraboot.framework.application.tenant.MetaContext.setEnvironmentId(10L);
            assertFalse(service.hasMenuPermission(7L, "model.import.create", 42L));
            com.auraboot.framework.application.tenant.MetaContext.setEnvironmentId(12L);
            assertTrue(service.hasMenuPermission(7L, "model.import.create", 42L));
        } finally { com.auraboot.framework.application.tenant.MetaContext.clear(); }
    }

    @Test
    void legacyMenuPermissionRetainsSubjectRestriction() {
        var mapper = org.mockito.Mockito.mock(com.auraboot.framework.menu.mapper.MenuMapper.class);
        var permissions = org.mockito.Mockito.mock(com.auraboot.framework.permission.service.UserPermissionService.class);
        var catalog = org.mockito.Mockito.mock(com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog.class);
        var service = releaseService(mapper, permissions, catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "applicationRuntimePrimaryEnabled", false);
        var subjects = org.mockito.Mockito.mock(com.auraboot.framework.permission.service.SubjectPermissionService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "subjectPermissionService", subjects);
        Menu menu = new Menu(); menu.setId(3L);
        org.mockito.Mockito.when(mapper.findByPermissionCode("platform.read")).thenReturn(menu);
        org.mockito.Mockito.when(permissions.hasPermission(7L, "platform.read")).thenReturn(true);
        org.mockito.Mockito.when(subjects.evaluateVisibility("menu", 3L, 7L)).thenReturn(false, true);
        assertFalse(service.hasMenuPermission(7L, "platform.read", 42L));
        assertTrue(service.hasMenuPermission(7L, "platform.read", 42L));
        org.mockito.Mockito.verifyNoInteractions(catalog);
    }

    @Test
    void authoringManagedMenusAreVisibleOnlyInPublishedEnvironments() {
        Menu legacy = new Menu();
        assertTrue(MenuEnvironmentScopeService.isVisibleIn(legacy, 10L));

        ExtensionBean extension = new ExtensionBean();
        extension.setDynamicProperty("authoringManaged", true);
        extension.setDynamicProperty("authoringEnvironmentIds", List.of(10L, "12"));
        Menu managed = new Menu();
        managed.setExtension(extension);

        assertTrue(MenuEnvironmentScopeService.isVisibleIn(managed, 10L));
        assertTrue(MenuEnvironmentScopeService.isVisibleIn(managed, 12L));
        assertFalse(MenuEnvironmentScopeService.isVisibleIn(managed, 11L));
        assertFalse(MenuEnvironmentScopeService.isVisibleIn(managed, null));
    }

    @Test
    @DisplayName("convertPathToResourceCode should convert valid paths correctly")
    void testConvertPathToResourceCode_validPaths() throws Exception {
        MenuServiceImpl service = new MenuServiceImpl();
        Method method = MenuServiceImpl.class.getDeclaredMethod("convertPathToResourceCode", String.class);
        method.setAccessible(true);

        // Test basic path conversion
        assertEquals("meta_models", method.invoke(service, "/meta/models"));
        assertEquals("meta", method.invoke(service, "/meta"));
        assertEquals("system", method.invoke(service, "/system"));

        // Test hyphen to underscore
        assertEquals("data_permissions", method.invoke(service, "/data-permissions"));
        assertEquals("api_connectors", method.invoke(service, "/api-connectors"));

        // Test multiple segments
        assertEquals("enterprise_members", method.invoke(service, "/enterprise/members"));
        assertEquals("meta_models_list", method.invoke(service, "/meta/models/list"));

        // Test without leading slash
        assertEquals("meta_models", method.invoke(service, "meta/models"));
    }

    @Test
    @DisplayName("convertPathToResourceCode should return null for invalid paths")
    void testConvertPathToResourceCode_invalidPaths() throws Exception {
        MenuServiceImpl service = new MenuServiceImpl();
        Method method = MenuServiceImpl.class.getDeclaredMethod("convertPathToResourceCode", String.class);
        method.setAccessible(true);

        // Null or empty
        assertNull(method.invoke(service, (String) null));
        assertNull(method.invoke(service, ""));

        // Starts with number (invalid after conversion)
        assertNull(method.invoke(service, "/123/test"));

        // Only special characters
        assertNull(method.invoke(service, "/---"));
    }

    @Test
    @DisplayName("convertPathToResourceCode should handle edge cases")
    void testConvertPathToResourceCode_edgeCases() throws Exception {
        MenuServiceImpl service = new MenuServiceImpl();
        Method method = MenuServiceImpl.class.getDeclaredMethod("convertPathToResourceCode", String.class);
        method.setAccessible(true);

        // Multiple slashes
        assertEquals("a_b_c", method.invoke(service, "/a//b///c"));

        // Mixed case
        assertEquals("meta_models", method.invoke(service, "/META/MODELS"));

        // Trailing slash
        assertEquals("meta_models", method.invoke(service, "/meta/models/"));

        // Special characters are removed (not replaced with underscore)
        assertEquals("testpath", method.invoke(service, "/test@path!"));
    }
}
