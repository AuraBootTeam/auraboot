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
