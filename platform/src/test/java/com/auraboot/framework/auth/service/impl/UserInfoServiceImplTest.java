package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.UserInfoResponse;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserPreferenceService;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserInfoServiceImpl")
class UserInfoServiceImplTest {

    @Mock private UserService userService;
    @Mock private RoleMapper roleMapper;
    @Mock private UserPermissionService userPermissionService;
    @Mock private PermissionMapper permissionMapper;
    @Mock private UserPreferenceService userPreferenceService;
    @Mock private TenantPreferenceService tenantPreferenceService;
    @Mock private TenantMemberService tenantMemberService;

    private UserInfoServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserInfoServiceImpl(userService, roleMapper, userPermissionService,
                permissionMapper, userPreferenceService, tenantPreferenceService);
        ReflectionTestUtils.setField(service, "tenantMemberService", tenantMemberService);
        MetaContext.setContext(10L, 1L, "u-1", "user");
        MetaContext.setMemberId(99L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private User user() {
        User u = new User();
        u.setId(1L);
        u.setPid("u-1");
        u.setNickName("Nick");
        u.setUserName("nick");
        u.setEmail("a@b.com");
        u.setMobile("13800");
        u.setImgId("img");
        return u;
    }

    private Role role(String code) {
        Role r = new Role();
        r.setId(1L);
        r.setCode(code);
        r.setName(code);
        return r;
    }

    private Permission perm(String code) {
        Permission p = new Permission();
        p.setCode(code);
        return p;
    }

    @Test
    @DisplayName("buildCurrentUserInfo for admin returns all permission codes")
    void adminAllPermissions() {
        when(userService.findByUserId(1L)).thenReturn(user());
        when(tenantMemberService.getTenantNameById(10L)).thenReturn("Acme");
        when(roleMapper.findByMemberIdAndTenantId(99L, 10L)).thenReturn(List.of(role("tenant_admin")));
        when(permissionMapper.selectList(any())).thenReturn(List.of(perm("p.read"), perm("p.write")));
        when(userPreferenceService.getPreferencesByPrefix(1L, "ui.")).thenReturn(Map.of());
        when(tenantPreferenceService.getPreferencesByPrefix(10L, "ui.")).thenReturn(Map.of());

        UserInfoResponse resp = service.buildCurrentUserInfo(1L, "u-1", 10L);
        assertEquals("Nick", resp.getUser().getName());
        assertEquals("Acme", resp.getUser().getTenantName());
        assertEquals(2, resp.getPermissions().getPermissionCodes().size());
        assertEquals(UserInfoResponse.PreferencesDTO.DEFAULT_TIMEZONE, resp.getPreferences().getTimezone());
    }

    @Test
    @DisplayName("non-admin uses userPermissionService to resolve permissions")
    void nonAdminViaUserPermissions() {
        when(userService.findByUserId(1L)).thenReturn(user());
        when(roleMapper.findByMemberIdAndTenantId(99L, 10L)).thenReturn(List.of(role("custom_role")));
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(java.util.Set.of(101L, 102L));
        when(permissionMapper.findByIds(any())).thenReturn(List.of(perm("p.x"), perm("p.y")));
        when(userPreferenceService.getPreferencesByPrefix(1L, "ui.")).thenReturn(Map.of());
        when(tenantPreferenceService.getPreferencesByPrefix(10L, "ui.")).thenReturn(Map.of());

        UserInfoResponse resp = service.buildCurrentUserInfo(1L, "u-1", 10L);
        assertEquals(2, resp.getPermissions().getPermissionCodes().size());
    }

    @Test
    @DisplayName("non-admin with no permissions returns empty list")
    void nonAdminNoPermissions() {
        when(userService.findByUserId(1L)).thenReturn(user());
        when(roleMapper.findByMemberIdAndTenantId(99L, 10L)).thenReturn(List.of(role("custom_role")));
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(java.util.Set.of());
        when(userPreferenceService.getPreferencesByPrefix(1L, "ui.")).thenReturn(Map.of());
        when(tenantPreferenceService.getPreferencesByPrefix(10L, "ui.")).thenReturn(Map.of());

        UserInfoResponse resp = service.buildCurrentUserInfo(1L, "u-1", 10L);
        assertEquals(0, resp.getPermissions().getPermissionCodes().size());
    }

    @Test
    @DisplayName("null tenantId returns empty roles/permissions and uses defaults")
    void nullTenantId() {
        when(userService.findByUserId(1L)).thenReturn(user());
        when(userPreferenceService.getPreferencesByPrefix(1L, "ui.")).thenReturn(Map.of());

        UserInfoResponse resp = service.buildCurrentUserInfo(1L, "u-1", null);
        assertEquals(0, resp.getPermissions().getRoles().size());
        assertEquals(0, resp.getPermissions().getPermissionCodes().size());
    }

    @Test
    @DisplayName("preferences resolved from user prefs first, then tenant, then default")
    void preferencesResolutionOrder() {
        when(userService.findByUserId(1L)).thenReturn(user());
        when(roleMapper.findByMemberIdAndTenantId(99L, 10L)).thenReturn(List.of());
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(java.util.Set.of());

        JsonNode userTz = JsonNodeFactory.instance.textNode("Asia/Shanghai");
        JsonNode tenantDate = JsonNodeFactory.instance.textNode("DD/MM/YYYY");
        when(userPreferenceService.getPreferencesByPrefix(1L, "ui."))
                .thenReturn(Map.of("ui.timezone", userTz));
        when(tenantPreferenceService.getPreferencesByPrefix(10L, "ui."))
                .thenReturn(Map.of("ui.date.format", tenantDate));

        UserInfoResponse resp = service.buildCurrentUserInfo(1L, "u-1", 10L);
        assertEquals("Asia/Shanghai", resp.getPreferences().getTimezone());
        assertEquals("DD/MM/YYYY", resp.getPreferences().getDateFormat());
        assertEquals(UserInfoResponse.PreferencesDTO.DEFAULT_DATETIME_FORMAT,
                resp.getPreferences().getDatetimeFormat());
    }

    @Test
    @DisplayName("user name falls back to userName when nickName null")
    void usernameFallback() {
        User u = user();
        u.setNickName(null);
        when(userService.findByUserId(1L)).thenReturn(u);
        when(userPreferenceService.getPreferencesByPrefix(1L, "ui.")).thenReturn(Map.of());

        UserInfoResponse resp = service.buildCurrentUserInfo(1L, "u-1", null);
        assertEquals("nick", resp.getUser().getName());
        assertNotNull(resp.getUser().getEmail());
    }
}
