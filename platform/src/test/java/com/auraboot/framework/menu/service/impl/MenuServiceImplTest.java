package com.auraboot.framework.menu.service.impl;

import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuEnvironmentScopeService;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.service.SubjectPermissionService;
import com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

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

    private Menu navigationLeaf(long id, String code, String path, String permission) {
        Menu menu = new Menu();
        menu.setId(id); menu.setCode(code); menu.setType(1); menu.setVisible(true);
        menu.setPath(path); menu.setPermissionCode(permission);
        return menu;
    }

    @Test
    void combinedNavigationDeduplicatesLegacyAndReleaseRoutesAfterPermissionFiltering() {
        var mapper = org.mockito.Mockito.mock(com.auraboot.framework.menu.mapper.MenuMapper.class);
        var permissions = org.mockito.Mockito.mock(com.auraboot.framework.permission.service.UserPermissionService.class);
        var catalog = org.mockito.Mockito.mock(com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog.class);
        var subjects = org.mockito.Mockito.mock(com.auraboot.framework.permission.service.SubjectPermissionService.class);
        var service = releaseService(mapper, permissions, catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "subjectPermissionService", subjects);
        org.mockito.Mockito.when(permissions.getUserPermissionCodes(7L)).thenReturn(java.util.Set.of("members.read"));
        org.mockito.Mockito.when(mapper.findVisibleDirectoriesAndMenus()).thenReturn(List.of(
                navigationLeaf(1, "roles", "/enterprise/permissions", null),
                navigationLeaf(2, "permission-relations", "/enterprise/permissions", null),
                navigationLeaf(3, "denied-member", "/p/tenant_member", "members.manage"),
                navigationLeaf(4, "subject-denied-child", "/xy/child", null)));
        org.mockito.Mockito.when(subjects.batchEvaluateVisibility("menu", List.of(1L, 2L, 3L, 4L), 7L))
                .thenReturn(java.util.Map.of(4L, false));
        org.mockito.Mockito.when(catalog.menuTree(42L, "example")).thenAnswer(call -> List.of(
                navigationLeaf(5, "customer-accounts", "/p/tenant_member", "members.read"),
                navigationLeaf(6, "release-roles", "/enterprise/permissions", null),
                navigationLeaf(7, "child", "/xy/child", null)));
        List<Menu> navigation = service.getUserMenuTree(7L, 42L);
        assertEquals(List.of("roles", "customer-accounts", "child"), navigation.stream().map(Menu::getCode).toList());
        assertEquals(3, navigation.stream().map(Menu::getPath).distinct().count());
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
    void authorizedLeafRetainsNestedDirectoriesWithoutGrantingSiblingAccess() {
        Menu root = menu(1L, null, 0, "system_management");
        Menu nested = menu(2L, 1L, 0, "unrelated_group_permission");
        Menu allowed = menu(3L, 2L, 1, "ai_center");
        Menu denied = menu(4L, 2L, 1, "system_management");
        Set<String> grants = Set.of("ai_center");
        var tree = userTree(List.of(root, nested, allowed, denied), grants, Map.of());
        assertEquals(List.of(root), tree);
        assertEquals(List.of(nested), root.getChildren());
        assertEquals(List.of(allowed), nested.getChildren());
        assertEquals(Set.of("ai_center"), grants);
        assertEquals("system_management", root.getPermissionCode());
    }

    @Test
    void directoryWithNoAuthorizedChildrenIsPruned() {
        assertTrue(userTree(List.of(menu(1L, null, 0, "system_management"),
                menu(2L, 1L, 1, "ai_center")), Set.of(), Map.of()).isEmpty());
    }

    @Test
    void explicitDirectoryVisibilityDenialStillHidesItsAuthorizedDescendants() {
        assertTrue(userTree(List.of(menu(1L, null, 0, "system_management"),
                menu(2L, 1L, 1, "ai_center")), Set.of("ai_center"), Map.of(1L, false)).isEmpty());
    }

    @Test
    void actionableParentStillRequiresItsOwnPermission() {
        assertTrue(userTree(List.of(menu(1L, null, 1, "system_management"),
                menu(2L, 1L, 1, "ai_center")), Set.of("ai_center"), Map.of()).isEmpty());
    }

    @Test
    void releaseDirectoriesAlsoRetainOnlyAuthorizedChildren() {
        MenuServiceImpl service = new MenuServiceImpl();
        MenuMapper mapper = mock(MenuMapper.class);
        UserPermissionService permissions = mock(UserPermissionService.class);
        ApplicationRuntimeDefinitionCatalog catalog = mock(ApplicationRuntimeDefinitionCatalog.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userPermissionService", permissions);
        ReflectionTestUtils.setField(service, "applicationRuntimeDefinitionCatalog", catalog);
        ReflectionTestUtils.setField(service, "applicationRuntimePrimaryEnabled", true);
        ReflectionTestUtils.setField(service, "defaultApplicationCode", "fixture");
        when(mapper.findVisibleDirectoriesAndMenus()).thenReturn(List.of());
        when(permissions.getUserPermissionCodes(7L)).thenReturn(Set.of("ai_center"));
        Menu root = menu(1L, null, 0, "system_management");
        Menu allowed = menu(2L, 1L, 1, "ai_center");
        root.setChildren(List.of(allowed, menu(3L, 1L, 1, "system_management")));
        when(catalog.menuTree(5L, "fixture")).thenReturn(List.of(root));
        assertEquals(List.of(root), service.getUserMenuTree(7L, 5L));
        assertEquals(List.of(allowed), root.getChildren());
    }

    private List<Menu> userTree(List<Menu> menus, Set<String> grants, Map<Long, Boolean> visibility) {
        MenuServiceImpl service = new MenuServiceImpl();
        MenuMapper mapper = mock(MenuMapper.class);
        UserPermissionService permissions = mock(UserPermissionService.class);
        SubjectPermissionService subjects = mock(SubjectPermissionService.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userPermissionService", permissions);
        ReflectionTestUtils.setField(service, "subjectPermissionService", subjects);
        when(mapper.findVisibleDirectoriesAndMenus()).thenReturn(menus);
        when(permissions.getUserPermissionCodes(7L)).thenReturn(grants);
        when(subjects.batchEvaluateVisibility(eq("menu"), anyList(), eq(7L))).thenReturn(visibility);
        return service.getUserMenuTree(7L, 5L);
    }

    private Menu menu(Long id, Long parent, int type, String permission) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setParentId(parent);
        menu.setType(type);
        menu.setPermissionCode(permission);
        menu.setVisible(true);
        return menu;
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
