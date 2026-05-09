package com.auraboot.framework.view.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.view.dto.AutoSaveViewRequest;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.dto.SavedViewUpdateRequest;
import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SavedViewServiceImplTest {

    @Mock SavedViewMapper savedViewMapper;
    @Mock UserPermissionService userPermissionService;
    @Mock CurrentUserTeamResolver currentUserTeamResolver;
    @Mock TeamMapper teamMapper;

    @InjectMocks SavedViewServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 7L, "user_pid", "alice");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private SavedViewCreateRequest createReq(String scope, String teamId) {
        SavedViewCreateRequest r = new SavedViewCreateRequest();
        r.setName("My View");
        r.setModelCode("crm.lead");
        r.setPageKey("crm/leads");
        r.setScope(scope);
        r.setTeamId(teamId);
        r.setViewType("table");
        r.setViewConfig(new ViewConfig());
        r.setIsDefault(true);
        return r;
    }

    @Test
    void create_personal_succeeds_andClearsPersonalDefaults() {
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        SavedViewDTO dto = service.create(createReq("personal", null));

        assertEquals("My View", dto.getName());
        verify(savedViewMapper).clearPersonalDefaultFlag("crm.lead", "crm/leads", "user_pid");
        verify(savedViewMapper).insertSavedView(any(SavedView.class));
    }

    @Test
    void create_team_validatesMembership() {
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        SavedViewCreateRequest req = createReq("team", "teamA");
        assertNotNull(service.create(req));

        // not a member of teamB → forbidden
        SavedViewCreateRequest reqB = createReq("team", "teamB");
        assertThrows(ValidationException.class, () -> service.create(reqB));
    }

    @Test
    void create_missingName_orModelCode_fails() {
        SavedViewCreateRequest r = new SavedViewCreateRequest();
        assertThrows(ValidationException.class, () -> service.create(r));
        r.setName("x");
        assertThrows(ValidationException.class, () -> service.create(r));
        r.setModelCode("m");
        r.setScope("team"); // team but no teamId
        assertThrows(ValidationException.class, () -> service.create(r));
    }

    @Test
    void create_duplicateName_fails() {
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), eq("My View"), anyString(), isNull()))
                .thenReturn(1);
        assertThrows(ValidationException.class, () -> service.create(createReq("personal", null)));
    }

    @Test
    void findByPid_notFound_returnsNull() {
        when(savedViewMapper.findByPid("missing")).thenReturn(null);
        assertNull(service.findByPid("missing"));
    }

    @Test
    void findByPid_personal_owner_returnsDto() {
        SavedView v = new SavedView();
        v.setPid("p1"); v.setScope("personal"); v.setOwnerId("user_pid");
        v.setModelCode("m"); v.setPageKey("k");
        when(savedViewMapper.findByPid("p1")).thenReturn(v);
        SavedViewDTO dto = service.findByPid("p1");
        assertEquals("p1", dto.getPid());
    }

    @Test
    void findByPid_personal_otherOwner_forbidden() {
        SavedView v = new SavedView();
        v.setPid("p1"); v.setScope("personal"); v.setOwnerId("someoneelse");
        when(savedViewMapper.findByPid("p1")).thenReturn(v);
        assertThrows(ValidationException.class, () -> service.findByPid("p1"));
    }

    @Test
    void findByPid_global_alwaysVisible() {
        SavedView v = new SavedView();
        v.setPid("g1"); v.setScope("global");
        when(savedViewMapper.findByPid("g1")).thenReturn(v);
        assertNotNull(service.findByPid("g1"));
    }

    @Test
    void update_notFound_throws() {
        when(savedViewMapper.findByPid("x")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.update("x", new SavedViewUpdateRequest()));
    }

    @Test
    void update_personal_byOwner_renamesAndChecksUniqueness() {
        SavedView v = new SavedView();
        v.setPid("p1"); v.setScope("personal"); v.setOwnerId("user_pid");
        v.setName("Old"); v.setModelCode("m"); v.setPageKey("k");
        when(savedViewMapper.findByPid("p1")).thenReturn(v);
        when(savedViewMapper.countByNameForUser(eq("m"), eq("k"), eq("New"), eq("user_pid"), eq("p1")))
                .thenReturn(0);

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        req.setName("New");
        SavedViewDTO dto = service.update("p1", req);

        assertEquals("New", dto.getName());
        verify(savedViewMapper).updateSavedView(any(SavedView.class));
    }

    @Test
    void delete_notFound_throws() {
        when(savedViewMapper.findByPid("x")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.delete("x"));
    }

    @Test
    void delete_personal_byOwner_succeeds() {
        SavedView v = new SavedView();
        v.setId(11L); v.setPid("p1"); v.setScope("personal"); v.setOwnerId("user_pid");
        when(savedViewMapper.findByPid("p1")).thenReturn(v);
        service.delete("p1");
        verify(savedViewMapper).deleteById(11L);
    }

    @Test
    void getAccessibleViews_resolvesTeamIdsAndMaps() {
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("tA"));
        SavedView v = new SavedView();
        v.setPid("p1"); v.setScope("personal"); v.setOwnerId("user_pid");
        when(savedViewMapper.findAccessibleViews(eq("m"), eq("k"), eq("user_pid"), eq(List.of("tA"))))
                .thenReturn(List.of(v));
        List<SavedViewDTO> result = service.getAccessibleViews("m", "k");
        assertEquals(1, result.size());
    }

    @Test
    void getDefaultView_null_returnsNull() {
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of());
        when(savedViewMapper.findDefaultView(any(), any(), any(), any())).thenReturn(null);
        assertNull(service.getDefaultView("m", "k"));
    }

    @Test
    void setAsDefault_personalByOwner_updatesAndClearsDefaults() {
        SavedView v = new SavedView();
        v.setPid("p1"); v.setScope("personal"); v.setOwnerId("user_pid");
        v.setModelCode("m"); v.setPageKey("k");
        when(savedViewMapper.findByPid("p1")).thenReturn(v);
        SavedViewDTO dto = service.setAsDefault("p1");
        assertNotNull(dto);
        verify(savedViewMapper).clearPersonalDefaultFlag("m", "k", "user_pid");
    }

    @Test
    void duplicate_global_withoutManagePermission_fallsBackToPersonal() {
        SavedView src = new SavedView();
        src.setPid("g1"); src.setScope("global");
        src.setModelCode("m"); src.setPageKey("k");
        src.setViewConfig(new ViewConfig());
        when(savedViewMapper.findByPid("g1")).thenReturn(src);
        when(userPermissionService.hasPermission(7L, MetaPermission.VIEW_MANAGE)).thenReturn(false);
        when(savedViewMapper.countByNameForUser(any(), any(), any(), any(), any())).thenReturn(0);

        SavedViewDTO dto = service.duplicate("g1", "Dup");

        assertEquals("personal", dto.getScope());
    }

    @Test
    void autoSave_existingImplicit_mergesConfig() {
        SavedView existing = new SavedView();
        existing.setId(1L); existing.setPid("imp"); existing.setOwnerId("user_pid");
        existing.setViewConfig(new ViewConfig());
        when(savedViewMapper.findImplicitView("m", "k", "user_pid")).thenReturn(existing);

        AutoSaveViewRequest req = new AutoSaveViewRequest();
        req.setModelCode("m"); req.setPageKey("k");
        ViewConfig incoming = new ViewConfig();
        incoming.setColumns(List.of());
        req.setViewConfig(incoming);

        SavedViewDTO dto = service.autoSave(req);
        assertEquals("imp", dto.getPid());
        verify(savedViewMapper).updateSavedView(existing);
    }

    @Test
    void autoSave_noExisting_createsImplicit_andClearsDefaults() {
        when(savedViewMapper.findImplicitView(any(), any(), any())).thenReturn(null);
        AutoSaveViewRequest req = new AutoSaveViewRequest();
        req.setModelCode("m"); req.setPageKey("k");
        req.setViewConfig(new ViewConfig());

        service.autoSave(req);

        verify(savedViewMapper).clearPersonalDefaultFlag("m", "k", "user_pid");
        verify(savedViewMapper).insertSavedView(any(SavedView.class));
    }

    @Test
    void isNameUnique_delegatesToMapper() {
        when(savedViewMapper.countByNameForUser("m", "k", "n", "user_pid", null)).thenReturn(0);
        assertTrue(service.isNameUnique("m", "k", "n", null));
        when(savedViewMapper.countByNameForUser("m", "k", "n", "user_pid", null)).thenReturn(1);
        assertFalse(service.isNameUnique("m", "k", "n", null));
    }

    @Test
    void teamScope_dtoPopulatesTeamName() {
        when(savedViewMapper.countByNameForUser(any(), any(), any(), any(), any())).thenReturn(0);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("tA"));
        Team team = new Team(); team.setName("Team A");
        when(teamMapper.findByPid("tA")).thenReturn(team);

        SavedViewCreateRequest req = createReq("team", "tA");
        SavedViewDTO dto = service.create(req);
        assertEquals("Team A", dto.getTeamName());
    }
}
