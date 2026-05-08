package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.dashboard.dto.DashboardCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.dto.DashboardQueryRequest;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardServiceImpl")
class DashboardServiceImplTest {

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
    @DisplayName("create rejects when title blank")
    void createRequiresTitle() {
        DashboardCreateRequest req = new DashboardCreateRequest();
        assertThrows(ValidationException.class, () -> service.create(req));
    }

    @Test
    @DisplayName("create rejects team scope without teamId")
    void createTeamScopeRequiresTeamId() {
        DashboardCreateRequest req = new DashboardCreateRequest();
        req.setTitle("t");
        req.setScope("team");
        assertThrows(ValidationException.class, () -> service.create(req));
    }

    @Test
    @DisplayName("create rejects when code already exists")
    void createCodeConflict() {
        DashboardCreateRequest req = new DashboardCreateRequest();
        req.setTitle("t");
        req.setCode("dup");
        when(dashboardMapper.countByCode(eq(10L), eq("dup"), eq(null))).thenReturn(1);
        assertThrows(ValidationException.class, () -> service.create(req));
    }

    @Test
    @DisplayName("create persists with default scope, generates code, records version")
    void createHappyPath() {
        DashboardCreateRequest req = new DashboardCreateRequest();
        req.setTitle("My Title");
        when(dashboardMapper.countByCode(any(), any(), any())).thenReturn(0);

        DashboardDTO dto = service.create(req);
        assertEquals("personal", dto.getScope());
        assertEquals("My Title", dto.getTitle());
        verify(dashboardMapper).insertDashboard(any(Dashboard.class));
        verify(versionHistoryService).recordVersion(eq("dashboard"), any(), eq("create"), eq(null));
    }

    @Test
    @DisplayName("create with isDefault=true clears existing personal default")
    void createClearsDefault() {
        DashboardCreateRequest req = new DashboardCreateRequest();
        req.setTitle("t");
        req.setIsDefault(true);
        when(dashboardMapper.countByCode(any(), any(), any())).thenReturn(0);

        service.create(req);
        verify(dashboardMapper).clearPersonalDefaultFlag(10L, "u-1");
    }

    @Test
    @DisplayName("findByPid returns null when missing")
    void findByPidMissing() {
        when(dashboardMapper.findByPid("none")).thenReturn(null);
        assertNull(service.findByPid("none"));
    }

    @Test
    @DisplayName("findByPid blocks non-owner of personal dashboard")
    void findByPidPersonalForbidden() {
        Dashboard d = fixture("p1", "personal", "u-other");
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        assertThrows(ValidationException.class, () -> service.findByPid("p1"));
    }

    @Test
    @DisplayName("findByPid allows global dashboard for any user")
    void findByPidGlobalAllowed() {
        Dashboard d = fixture("g1", "global", null);
        when(dashboardMapper.findByPid("g1")).thenReturn(d);
        assertNotNull(service.findByPid("g1"));
    }

    @Test
    @DisplayName("findByPid allows team dashboard for member")
    void findByPidTeamAllowed() {
        Dashboard d = fixture("t1", "team", null);
        d.setTeamId("team-x");
        d.setCreatedBy("u-other");
        when(dashboardMapper.findByPid("t1")).thenReturn(d);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("team-x"));
        assertNotNull(service.findByPid("t1"));
    }

    @Test
    @DisplayName("findByPid blocks non-team-member")
    void findByPidTeamForbidden() {
        Dashboard d = fixture("t1", "team", null);
        d.setTeamId("team-x");
        d.setCreatedBy("u-other");
        when(dashboardMapper.findByPid("t1")).thenReturn(d);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("other"));
        assertThrows(ValidationException.class, () -> service.findByPid("t1"));
    }

    @Test
    @DisplayName("findByCode returns null when missing")
    void findByCodeMissing() {
        when(dashboardMapper.findByCode(10L, "x")).thenReturn(null);
        assertNull(service.findByCode("x"));
    }

    @Test
    @DisplayName("update throws when missing")
    void updateMissing() {
        when(dashboardMapper.findByPid("none")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.update("none", new DashboardUpdateRequest()));
    }

    @Test
    @DisplayName("update applies non-null fields and records version")
    void updateHappy() {
        Dashboard d = fixture("p1", "personal", "u-1");
        when(dashboardMapper.findByPid("p1")).thenReturn(d);

        DashboardUpdateRequest req = new DashboardUpdateRequest();
        req.setTitle("New Title");
        req.setDescription("desc");
        req.setSortOrder(5);
        req.setIsDefault(true);

        DashboardDTO out = service.update("p1", req);
        assertEquals("New Title", out.getTitle());
        verify(dashboardMapper).updateDashboard(d);
        verify(dashboardMapper).clearPersonalDefaultFlag(10L, "u-1");
        verify(versionHistoryService).recordVersion(eq("dashboard"), eq("p1"), eq("update"), eq(null));
    }

    @Test
    @DisplayName("update blocks non-owner of personal dashboard")
    void updatePersonalForbidden() {
        Dashboard d = fixture("p1", "personal", "u-other");
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        assertThrows(ValidationException.class, () -> service.update("p1", new DashboardUpdateRequest()));
    }

    @Test
    @DisplayName("update global blocked without DASHBOARD_MANAGE permission")
    void updateGlobalForbidden() {
        Dashboard d = fixture("g1", "global", null);
        d.setCreatedBy("u-other");
        when(dashboardMapper.findByPid("g1")).thenReturn(d);
        when(userPermissionService.hasPermission(eq(1L), any(String.class))).thenReturn(false);
        assertThrows(ValidationException.class, () -> service.update("g1", new DashboardUpdateRequest()));
    }

    @Test
    @DisplayName("update global allowed with DASHBOARD_MANAGE permission")
    void updateGlobalAllowedWithPermission() {
        Dashboard d = fixture("g1", "global", null);
        d.setCreatedBy("u-other");
        when(dashboardMapper.findByPid("g1")).thenReturn(d);
        when(userPermissionService.hasPermission(eq(1L), any(String.class))).thenReturn(true);
        DashboardDTO out = service.update("g1", new DashboardUpdateRequest());
        assertNotNull(out);
    }

    @Test
    @DisplayName("delete throws when missing")
    void deleteMissing() {
        when(dashboardMapper.findByPid("none")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.delete("none"));
    }

    @Test
    @DisplayName("delete soft-deletes when authorized")
    void deleteHappy() {
        Dashboard d = fixture("p1", "personal", "u-1");
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        service.delete("p1");
        verify(dashboardMapper).deleteById(100L);
    }

    @Test
    @DisplayName("getAccessibleDashboards delegates with team ids")
    void getAccessibleDashboards() {
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("t1"));
        when(dashboardMapper.findAccessibleDashboards(eq(10L), eq("u-1"), any(), any(), any(), any()))
                .thenReturn(List.of(fixture("p1", "personal", "u-1")));
        DashboardQueryRequest req = new DashboardQueryRequest();
        assertEquals(1, service.getAccessibleDashboards(req).size());
    }

    @Test
    @DisplayName("getPersonalDashboards delegates")
    void getPersonalDashboards() {
        when(dashboardMapper.findPersonalDashboards(10L, "u-1"))
                .thenReturn(List.of(fixture("p1", "personal", "u-1")));
        assertEquals(1, service.getPersonalDashboards().size());
    }

    @Test
    @DisplayName("getGlobalDashboards delegates")
    void getGlobalDashboards() {
        when(dashboardMapper.findGlobalDashboards(10L)).thenReturn(List.of(fixture("g1", "global", null)));
        assertEquals(1, service.getGlobalDashboards().size());
    }

    @Test
    @DisplayName("getDefaultDashboard returns null when none")
    void getDefaultNull() {
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of());
        when(dashboardMapper.findDefaultDashboard(any(), any(), any())).thenReturn(null);
        assertNull(service.getDefaultDashboard());
    }

    @Test
    @DisplayName("getDefaultDashboard returns DTO when found")
    void getDefaultFound() {
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of());
        when(dashboardMapper.findDefaultDashboard(any(), any(), any()))
                .thenReturn(fixture("p1", "personal", "u-1"));
        assertNotNull(service.getDefaultDashboard());
    }

    @Test
    @DisplayName("setAsDefault throws when missing")
    void setAsDefaultMissing() {
        when(dashboardMapper.findByPid("none")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.setAsDefault("none"));
    }

    @Test
    @DisplayName("setAsDefault clears other defaults and updates")
    void setAsDefaultHappy() {
        Dashboard d = fixture("p1", "personal", "u-1");
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        service.setAsDefault("p1");
        verify(dashboardMapper).clearPersonalDefaultFlag(10L, "u-1");
        assertTrue(d.getIsDefault());
    }

    @Test
    @DisplayName("publish rejects widgets missing config")
    void publishRejectsBadWidgets() throws Exception {
        Dashboard d = fixture("p1", "personal", "u-1");
        d.setWidgets(objectMapper.readTree("[{\"id\":\"w1\"}]"));
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        assertThrows(ValidationException.class, () -> service.publish("p1"));
    }

    @Test
    @DisplayName("publish rejects widget missing title")
    void publishRejectsMissingTitle() throws Exception {
        Dashboard d = fixture("p1", "personal", "u-1");
        d.setWidgets(objectMapper.readTree("[{\"id\":\"w1\",\"config\":{\"foo\":\"bar\"}}]"));
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        assertThrows(ValidationException.class, () -> service.publish("p1"));
    }

    @Test
    @DisplayName("publish allows empty widgets array")
    void publishEmptyWidgetsAllowed() throws Exception {
        Dashboard d = fixture("p1", "personal", "u-1");
        d.setWidgets(objectMapper.readTree("[]"));
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        service.publish("p1");
        assertEquals(StatusConstants.PUBLISHED, d.getStatus());
    }

    @Test
    @DisplayName("publish accepts well-formed widget with title in config")
    void publishHappy() throws Exception {
        Dashboard d = fixture("p1", "personal", "u-1");
        d.setWidgets(objectMapper.readTree("[{\"id\":\"w1\",\"config\":{\"title\":\"Hi\"}}]"));
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        service.publish("p1");
        assertEquals(StatusConstants.PUBLISHED, d.getStatus());
        verify(versionHistoryService).recordVersion(eq("dashboard"), eq("p1"), eq("publish"), eq(null));
    }

    @Test
    @DisplayName("unpublish sets status to draft and records version")
    void unpublishHappy() {
        Dashboard d = fixture("p1", "personal", "u-1");
        d.setStatus(StatusConstants.PUBLISHED);
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        service.unpublish("p1");
        assertEquals(StatusConstants.DRAFT, d.getStatus());
        verify(versionHistoryService).recordVersion(eq("dashboard"), eq("p1"), eq("unpublish"), eq(null));
    }

    @Test
    @DisplayName("duplicate copies and creates new dashboard with personal scope")
    void duplicate() {
        Dashboard src = fixture("src", "personal", "u-1");
        when(dashboardMapper.findByPid("src")).thenReturn(src);
        when(dashboardMapper.countByCode(any(), any(), any())).thenReturn(0);

        DashboardDTO dto = service.duplicate("src", "Copy");
        assertEquals("Copy", dto.getTitle());
        assertEquals("personal", dto.getScope());
    }

    @Test
    @DisplayName("isCodeUnique true when count == 0")
    void isCodeUniqueTrue() {
        when(dashboardMapper.countByCode(10L, "x", null)).thenReturn(0);
        assertTrue(service.isCodeUnique("x", null));
    }

    @Test
    @DisplayName("mountToMenu rejects non-published dashboard")
    void mountRejectsDraft() {
        Dashboard d = fixture("p1", "global", null);
        d.setStatus(StatusConstants.DRAFT);
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        assertThrows(ValidationException.class, () -> service.mountToMenu("p1", new MountMenuRequest()));
    }

    @Test
    @DisplayName("mountToMenu rejects non-global scope")
    void mountRejectsNonGlobal() {
        Dashboard d = fixture("p1", "personal", "u-1");
        d.setStatus(StatusConstants.PUBLISHED);
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        assertThrows(ValidationException.class, () -> service.mountToMenu("p1", new MountMenuRequest()));
    }

    @Test
    @DisplayName("mountToMenu idempotent when permission already exists")
    void mountIdempotent() {
        Dashboard d = fixture("p1", "global", null);
        d.setStatus(StatusConstants.PUBLISHED);
        d.setCode("d1");
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        when(permissionMapper.findByCode(any())).thenReturn(new Permission());

        service.mountToMenu("p1", new MountMenuRequest());
        verify(permissionMapper, never()).insert(any(Permission.class));
        verify(menuService, never()).createMenu(any(Menu.class));
    }

    @Test
    @DisplayName("mountToMenu fails when parent menu not found")
    void mountParentMissing() {
        Dashboard d = fixture("p1", "global", null);
        d.setStatus(StatusConstants.PUBLISHED);
        d.setCode("d1");
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        when(permissionMapper.findByCode(any())).thenReturn(null);
        when(menuService.getOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(ValidationException.class, () -> service.mountToMenu("p1", new MountMenuRequest()));
    }

    @Test
    @DisplayName("unmountFromMenu noop when not mounted")
    void unmountNotMounted() {
        Dashboard d = fixture("p1", "global", null);
        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        service.unmountFromMenu("p1");
        verify(menuService, never()).deleteMenu(any());
    }

    @Test
    @DisplayName("unmountFromMenu deletes menu and clears extension")
    void unmountClearsExtension() {
        Dashboard d = fixture("p1", "global", null);
        ObjectNode ext = objectMapper.createObjectNode();
        ext.put("menuMounted", true);
        ext.put("menuCode", "menu-x");
        d.setExtension(ext);

        when(dashboardMapper.findByPid("p1")).thenReturn(d);
        Menu m = new Menu();
        m.setId(50L);
        when(menuService.getOne(any(LambdaQueryWrapper.class))).thenReturn(m);
        Permission p = new Permission();
        p.setId(60L);
        when(permissionMapper.findByCode("menu-x")).thenReturn(p);

        service.unmountFromMenu("p1");
        verify(menuService).deleteMenu(50L);
        verify(permissionMapper).deleteById(60L);
        verify(dashboardMapper).updateExtension(eq("p1"), any());
    }

    @Test
    @DisplayName("getOrCreateWorkbench returns existing when present")
    void workbenchExisting() {
        Dashboard wb = fixture("wb", "workbench", "u-1");
        when(dashboardMapper.findWorkbench(10L, "u-1")).thenReturn(wb);
        assertEquals("wb", service.getOrCreateWorkbench().getPid());
        verify(dashboardMapper, never()).insertDashboard(any());
    }

    @Test
    @DisplayName("getOrCreateWorkbench creates new when missing")
    void workbenchCreated() {
        when(dashboardMapper.findWorkbench(10L, "u-1")).thenReturn(null);
        DashboardDTO dto = service.getOrCreateWorkbench();
        assertEquals("My Workbench", dto.getTitle());
        verify(dashboardMapper).insertDashboard(any(Dashboard.class));
    }
}
