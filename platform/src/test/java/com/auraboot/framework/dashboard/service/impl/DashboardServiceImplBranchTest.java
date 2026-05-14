package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.dashboard.dto.DashboardCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.dto.DashboardUpdateRequest;
import com.auraboot.framework.dashboard.dto.MountMenuRequest;
import com.auraboot.framework.dashboard.entity.Dashboard;
import com.auraboot.framework.dashboard.mapper.DashboardMapper;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Additional branch coverage for {@link DashboardServiceImpl} —
 * focuses on mountToMenu happy path, unpublish auto-unmount,
 * team-scope create/update validations, setAsDefault for global, and other
 * branches not covered by {@code DashboardServiceImplTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardServiceImpl branch coverage")
class DashboardServiceImplBranchTest {

    @Mock private DashboardMapper dashboardMapper;
    @Mock private VersionHistoryService versionHistoryService;
    @Mock private UserPermissionService userPermissionService;
    @Mock private CurrentUserTeamResolver currentUserTeamResolver;
    @Mock private MenuService menuService;
    @Mock private PermissionMapper permissionMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DashboardServiceImpl(dashboardMapper, objectMapper, versionHistoryService,
                userPermissionService, currentUserTeamResolver, menuService, permissionMapper);
        MetaContext.setContext(10L, 1L, "u-1", "user");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private Dashboard fixture(String pid, String scope, String ownerPid) {
        Dashboard d = new Dashboard();
        d.setId(100L);
        d.setPid(pid);
        d.setTenantId(10L);
        d.setCode("dash_" + pid);
        d.setTitle("Title");
        d.setScope(scope);
        d.setOwnerId(ownerPid);
        d.setStatus(StatusConstants.DRAFT);
        d.setCreatedBy("u-1");
        return d;
    }

    @Test
    @DisplayName("create team scope blocked when current user not in team")
    void createTeamUserNotMember() {
        DashboardCreateRequest req = new DashboardCreateRequest();
        req.setTitle("t");
        req.setScope("team");
        req.setTeamId("team-x");
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("other"));
        assertThrows(ValidationException.class, () -> service.create(req));
    }

    @Test
    @DisplayName("create team scope succeeds when current user is a team member")
    void createTeamUserMember() {
        DashboardCreateRequest req = new DashboardCreateRequest();
        req.setTitle("t");
        req.setScope("team");
        req.setTeamId("team-x");
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("team-x"));
        when(dashboardMapper.countByCode(any(), any(), any())).thenReturn(0);

        DashboardDTO dto = service.create(req);
        assertEquals("team", dto.getScope());
        assertEquals("team-x", dto.getTeamId());
        verify(dashboardMapper).insertDashboard(any(Dashboard.class));
    }

    @Test
    @DisplayName("update team-scope dashboard validates current user team membership")
    void updateTeamValidatesMembership() {
        Dashboard d = fixture("t1", "team", null);
        d.setTeamId("team-x");
        d.setCreatedBy("u-1"); // creator → write allowed
        when(dashboardMapper.findByPid("t1")).thenReturn(d);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("other"));

        // validateCurrentUserInTeam should reject because user is not in team-x
        assertThrows(ValidationException.class, () -> service.update("t1", new DashboardUpdateRequest()));
    }

    @Test
    @DisplayName("update team-scope writeAccess blocked for non-creator without DASHBOARD_TEAM_MANAGE")
    void updateTeamWriteForbidden() {
        Dashboard d = fixture("t1", "team", null);
        d.setTeamId("team-x");
        d.setCreatedBy("u-other");
        when(dashboardMapper.findByPid("t1")).thenReturn(d);
        when(userPermissionService.hasPermission(eq(1L), any(String.class))).thenReturn(false);
        assertThrows(ValidationException.class, () -> service.update("t1", new DashboardUpdateRequest()));
    }

    @Test
    @DisplayName("update team-scope writeAccess allowed via DASHBOARD_TEAM_MANAGE permission")
    void updateTeamWriteAllowed() {
        Dashboard d = fixture("t1", "team", null);
        d.setTeamId("team-x");
        d.setCreatedBy("u-other");
        when(dashboardMapper.findByPid("t1")).thenReturn(d);
        when(userPermissionService.hasPermission(eq(1L), any(String.class))).thenReturn(true);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("team-x"));
        d.setTeamId("team-x");

        DashboardDTO out = service.update("t1", new DashboardUpdateRequest());
        assertNotNull(out);
        verify(dashboardMapper).updateDashboard(d);
    }

    @Test
    @DisplayName("update applies layoutConfig, widgets, scope, teamId, extension when provided")
    void updateAllFieldsApplied() throws Exception {
        Dashboard d = fixture("p1", "personal", "u-1");
        when(dashboardMapper.findByPid("p1")).thenReturn(d);

        DashboardUpdateRequest req = new DashboardUpdateRequest();
        req.setLayoutConfig(objectMapper.readTree("{\"columns\":24}"));
        req.setWidgets(objectMapper.readTree("[{\"id\":\"w1\"}]"));
        req.setScope("personal");
        req.setTeamId("team-y");
        req.setExtension(objectMapper.createObjectNode().put("k", "v"));

        service.update("p1", req);
        assertEquals("team-y", d.getTeamId());
        assertNotNull(d.getLayoutConfig());
        assertNotNull(d.getWidgets());
        assertNotNull(d.getExtension());
    }

    @Test
    @DisplayName("update with isDefault=true on global dashboard does not call clearPersonalDefaultFlag")
    void updateGlobalIsDefaultSkipsPersonalClear() {
        Dashboard d = fixture("g1", "global", null);
        d.setCreatedBy("u-1"); // creator allowed
        when(dashboardMapper.findByPid("g1")).thenReturn(d);

        DashboardUpdateRequest req = new DashboardUpdateRequest();
        req.setIsDefault(true);

        service.update("g1", req);
        verify(dashboardMapper, never()).clearPersonalDefaultFlag(any(), any());
        assertTrue(d.getIsDefault());
    }

    @Test
    @DisplayName("setAsDefault on global dashboard does not clear personal defaults")
    void setAsDefaultGlobalSkipsPersonalClear() {
        Dashboard d = fixture("g1", "global", null);
        d.setCreatedBy("u-1");
        when(dashboardMapper.findByPid("g1")).thenReturn(d);

        service.setAsDefault("g1");
        verify(dashboardMapper, never()).clearPersonalDefaultFlag(any(), any());
        assertTrue(d.getIsDefault());
    }

    @Test
    @DisplayName("isCodeUnique returns false when count > 0")
    void isCodeUniqueFalse() {
        when(dashboardMapper.countByCode(10L, "dup", "exclude")).thenReturn(2);
        assertEquals(false, service.isCodeUnique("dup", "exclude"));
    }

    @Test
    @DisplayName("publish throws when dashboard missing")
    void publishMissing() {
        when(dashboardMapper.findByPid("none")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.publish("none"));
    }

    @Test
    @DisplayName("publish accepts widget with title at top-level (not in config)")
    void publishWidgetTopLevelTitle() throws Exception {
        Dashboard d = fixture("p1", "personal", "u-1");
        d.setWidgets(objectMapper.readTree(
                "[{\"id\":\"w1\",\"title\":\"Top\",\"config\":{\"foo\":\"bar\"}}]"));
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        service.publish("p1");
        assertEquals(StatusConstants.PUBLISHED, d.getStatus());
    }

    @Test
    @DisplayName("publish accepts localized widget title objects")
    void publishLocalizedWidgetTitle() throws Exception {
        Dashboard d = fixture("p1", "personal", "u-1");
        d.setWidgets(objectMapper.readTree("""
                [{
                  "id": "w1",
                  "title": { "zh-CN": "关键指标", "en": "Key Metrics" },
                  "config": { "dataSource": { "type": "namedQuery", "queryCode": "demo" } }
                }]
                """));
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        service.publish("p1");
        assertEquals(StatusConstants.PUBLISHED, d.getStatus());
    }

    @Test
    @DisplayName("publishRejectsBadWidgets uses 'i' as widgetId fallback when 'id' absent")
    void publishWidgetIdFromIField() throws Exception {
        Dashboard d = fixture("p1", "personal", "u-1");
        d.setWidgets(objectMapper.readTree("[{\"i\":\"i-1\"}]"));
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        ValidationException ex = assertThrows(ValidationException.class, () -> service.publish("p1"));
        assertTrue(ex.getMessage().contains("i-1"));
    }

    @Test
    @DisplayName("publishRejectsBadWidgets uses index= fallback when neither id nor i present")
    void publishWidgetIdFromIndex() throws Exception {
        Dashboard d = fixture("p1", "personal", "u-1");
        d.setWidgets(objectMapper.readTree("[{\"foo\":\"bar\"}]"));
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        ValidationException ex = assertThrows(ValidationException.class, () -> service.publish("p1"));
        assertTrue(ex.getMessage().contains("index=0"));
    }

    @Test
    @DisplayName("unpublish throws when dashboard missing")
    void unpublishMissing() {
        when(dashboardMapper.findByPid("none")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.unpublish("none"));
    }

    @Test
    @DisplayName("unpublish auto-unmounts when dashboard has menuMounted=true")
    void unpublishAutoUnmount() {
        Dashboard d = fixture("p1", "global", null);
        d.setStatus(StatusConstants.PUBLISHED);
        ObjectNode ext = objectMapper.createObjectNode();
        ext.put("menuMounted", true);
        ext.put("menuCode", "menu-x");
        d.setExtension(ext);

        // First findByPid returns the mounted dashboard; second (re-fetch after unmount) returns
        // a copy with cleared extension to mimic post-unmount state.
        Dashboard refetched = fixture("p1", "global", null);
        ObjectNode clearedExt = objectMapper.createObjectNode();
        refetched.setExtension(clearedExt);
        refetched.setStatus(StatusConstants.PUBLISHED);
        when(dashboardMapper.findByPid("p1")).thenReturn(d, refetched);

        // unmountFromMenu lookups
        Menu existingMenu = new Menu();
        existingMenu.setId(50L);
        when(menuService.getOne(any(LambdaQueryWrapper.class))).thenReturn(existingMenu);
        Permission existingPerm = new Permission();
        existingPerm.setId(60L);
        when(permissionMapper.findByCode("menu-x")).thenReturn(existingPerm);

        service.unpublish("p1");

        verify(menuService).deleteMenu(50L);
        verify(permissionMapper).deleteById(60L);
        // Status set on the re-fetched dashboard
        assertEquals(StatusConstants.DRAFT, refetched.getStatus());
        verify(versionHistoryService).recordVersion(eq("dashboard"), eq("p1"), eq("unpublish"), eq(null));
    }

    @Test
    @DisplayName("duplicate throws when source dashboard missing")
    void duplicateMissing() {
        when(dashboardMapper.findByPid("none")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.duplicate("none", "Copy"));
    }

    @Test
    @DisplayName("mountToMenu throws when dashboard missing")
    void mountMissing() {
        when(dashboardMapper.findByPid("none")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.mountToMenu("none", new MountMenuRequest()));
    }

    @Test
    @DisplayName("mountToMenu rejects when already-mounted (extension menuMounted=true)")
    void mountAlreadyMounted() {
        Dashboard d = fixture("p1", "global", null);
        d.setStatus(StatusConstants.PUBLISHED);
        ObjectNode ext = objectMapper.createObjectNode();
        ext.put("menuMounted", true);
        d.setExtension(ext);
        when(dashboardMapper.findByPid("p1")).thenReturn(d);

        assertThrows(ValidationException.class, () -> service.mountToMenu("p1", new MountMenuRequest()));
    }

    @Test
    @DisplayName("mountToMenu happy path creates Permission, Menu, and updates extension")
    void mountHappy() {
        Dashboard d = fixture("p1", "global", null);
        d.setStatus(StatusConstants.PUBLISHED);
        d.setCode("d-1");
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        when(permissionMapper.findByCode(any())).thenReturn(null);

        Menu parent = new Menu();
        parent.setId(7L);
        when(menuService.getOne(any(LambdaQueryWrapper.class))).thenReturn(parent);

        MountMenuRequest req = new MountMenuRequest();
        req.setParentCode("custom_parent");
        req.setIcon("layout");
        req.setOrderNo(99);

        service.mountToMenu("p1", req);

        verify(permissionMapper, times(1)).insert(any(Permission.class));

        ArgumentCaptor<Menu> menuCap = ArgumentCaptor.forClass(Menu.class);
        verify(menuService).createMenu(menuCap.capture());
        Menu created = menuCap.getValue();
        assertEquals("layout", created.getIcon());
        assertEquals(99, created.getOrderNo());
        assertEquals(7L, created.getParentId());
        assertTrue(created.getCode().startsWith("dashboard_view_"));
        assertEquals("/dashboards/view/d-1", created.getPath());
        verify(dashboardMapper).updateExtension(eq("p1"), any());
    }

    @Test
    @DisplayName("mountToMenu uses defaults when request fields are null")
    void mountWithDefaults() {
        Dashboard d = fixture("p1", "global", null);
        d.setStatus(StatusConstants.PUBLISHED);
        d.setCode("d-2");
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        when(permissionMapper.findByCode(any())).thenReturn(null);

        Menu parent = new Menu();
        parent.setId(8L);
        when(menuService.getOne(any(LambdaQueryWrapper.class))).thenReturn(parent);

        MountMenuRequest req = new MountMenuRequest();
        // Override defaults to nulls to exercise null-fallback branches
        req.setParentCode(null);
        req.setIcon(null);
        req.setOrderNo(null);

        service.mountToMenu("p1", req);

        ArgumentCaptor<Menu> menuCap = ArgumentCaptor.forClass(Menu.class);
        verify(menuService).createMenu(menuCap.capture());
        Menu created = menuCap.getValue();
        assertEquals("bar-chart", created.getIcon());
        assertEquals(50, created.getOrderNo());
    }

    @Test
    @DisplayName("unmountFromMenu throws when dashboard missing")
    void unmountMissing() {
        when(dashboardMapper.findByPid("none")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.unmountFromMenu("none"));
    }

    @Test
    @DisplayName("unmountFromMenu skips deletes when menu and permission are absent")
    void unmountNoExisting() {
        Dashboard d = fixture("p1", "global", null);
        ObjectNode ext = objectMapper.createObjectNode();
        ext.put("menuCode", "menu-y");
        d.setExtension(ext);

        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        when(menuService.getOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(permissionMapper.findByCode("menu-y")).thenReturn(null);

        service.unmountFromMenu("p1");

        verify(menuService, never()).deleteMenu(any());
        verify(permissionMapper, never()).deleteById(any(java.io.Serializable.class));
        // extension still updated
        verify(dashboardMapper).updateExtension(eq("p1"), any());
    }

    @Test
    @DisplayName("findByCode reads-with-access then returns DTO")
    void findByCodeFound() {
        Dashboard d = fixture("p1", "global", null);
        when(dashboardMapper.findByCode(10L, "code-x")).thenReturn(d);
        assertNotNull(service.findByCode("code-x"));
    }

    @Test
    @DisplayName("getOrCreateWorkbench builds with default layoutConfig and widgets when none")
    void workbenchDefaultsApplied() {
        when(dashboardMapper.findWorkbench(10L, "u-1")).thenReturn(null);
        DashboardDTO dto = service.getOrCreateWorkbench();
        assertEquals("workbench", dto.getScope());
        assertNotNull(dto.getLayoutConfig());
    }
}
