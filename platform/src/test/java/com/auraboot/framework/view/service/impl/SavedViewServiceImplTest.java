package com.auraboot.framework.view.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.view.dto.AutoSaveViewRequest;
import com.auraboot.framework.view.dto.SavedViewAuditEventDTO;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewCapabilityCheckRequest;
import com.auraboot.framework.view.dto.SavedViewCapabilityCheckResponse;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SavedViewServiceImplTest {

    @Mock SavedViewMapper savedViewMapper;
    @Mock PageSchemaMapper pageSchemaMapper;
    @Mock MetaModelService metaModelService;
    @Mock UserPermissionService userPermissionService;
    @Mock CurrentUserTeamResolver currentUserTeamResolver;
    @Mock TeamMapper teamMapper;
    @Mock AuditTrailService auditTrailService;
    @Mock UserService userService;

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
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        SavedViewDTO dto = service.create(createReq("personal", null));

        assertEquals("My View", dto.getName());
        verify(savedViewMapper).clearPersonalDefaultFlag("crm.lead", "crm/leads", "user_pid");
        verify(savedViewMapper).insertSavedView(any(SavedView.class));
    }

    @Test
    void create_team_validatesMembership() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
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
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), eq("My View"), anyString(), isNull()))
                .thenReturn(1);
        assertThrows(ValidationException.class, () -> service.create(createReq("personal", null)));
    }

    @Test
    void create_personalAtLimit_failsBeforeInsert() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);
        when(savedViewMapper.countActiveNonImplicitViewsForScope(
                eq("crm.lead"), eq("crm/leads"), eq("personal"), eq("user_pid"), isNull()))
                .thenReturn(10);

        ValidationException ex = assertThrows(
                ValidationException.class,
                () -> service.create(createReq("personal", null)));

        assertThat(ex.getMessage()).contains("limit").contains("10");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void create_teamAtLimit_failsBeforeInsert() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);
        when(savedViewMapper.countActiveNonImplicitViewsForScope(
                eq("crm.lead"), eq("crm/leads"), eq("team"), isNull(), eq("teamA")))
                .thenReturn(20);

        ValidationException ex = assertThrows(
                ValidationException.class,
                () -> service.create(createReq("team", "teamA")));

        assertThat(ex.getMessage()).contains("limit").contains("20");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void create_globalAtLimit_failsBeforeInsert() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);
        when(savedViewMapper.countActiveNonImplicitViewsForScope(
                eq("crm.lead"), eq("crm/leads"), eq("global"), isNull(), isNull()))
                .thenReturn(20);

        ValidationException ex = assertThrows(
                ValidationException.class,
                () -> service.create(createReq("global", null)));

        assertThat(ex.getMessage()).contains("limit").contains("20");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void create_advancedViewWithoutRequiredConfig_fails() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        SavedViewCreateRequest gantt = createReq("personal", null);
        gantt.setViewType("gantt");
        ViewConfig partial = new ViewConfig();
        partial.setGanttStartDateField("start_date");
        gantt.setViewConfig(partial);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(gantt));
        assertThat(ex.getMessage()).contains("ganttEndDateField");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void create_galleryViewWithoutImageField_fails() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        SavedViewCreateRequest gallery = createReq("personal", null);
        gallery.setViewType("gallery");
        ViewConfig partial = new ViewConfig();
        partial.setGalleryTitleField("name");
        gallery.setViewConfig(partial);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(gallery));
        assertThat(ex.getMessage()).contains("galleryImageField");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void create_treeViewWithoutParentField_fails() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        SavedViewCreateRequest tree = createReq("personal", null);
        tree.setViewType("tree");
        ViewConfig partial = new ViewConfig();
        partial.setTreeTitleField("name");
        tree.setViewConfig(partial);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(tree));
        assertThat(ex.getMessage()).contains("treeParentField");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void create_timelineViewWithoutStartAndResourceFields_fails() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        SavedViewCreateRequest timeline = createReq("personal", null);
        timeline.setViewType("timeline");
        timeline.setViewConfig(new ViewConfig());

        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(timeline));
        assertThat(ex.getMessage()).contains("timelineStartField", "timelineResourceField");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void create_calendarViewWithUnknownDateField_fails() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(metaModelService.getModelFields("crm.lead")).thenReturn(List.of(
                field("name", "string"),
                field("created_at", "datetime")
        ));
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        SavedViewCreateRequest calendar = createReq("personal", null);
        calendar.setViewType("calendar");
        ViewConfig config = new ViewConfig();
        config.setCalendarDateField("missing_date");
        calendar.setViewConfig(config);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(calendar));

        assertThat(ex.getMessage()).contains("UNKNOWN_FIELD", "calendarDateField", "missing_date");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void create_calendarViewWithTextDateField_fails() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(metaModelService.getModelFields("crm.lead")).thenReturn(List.of(
                field("name", "string"),
                field("created_at", "datetime")
        ));
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        SavedViewCreateRequest calendar = createReq("personal", null);
        calendar.setViewType("calendar");
        ViewConfig config = new ViewConfig();
        config.setCalendarDateField("name");
        calendar.setViewConfig(config);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(calendar));

        assertThat(ex.getMessage()).contains("INCOMPATIBLE_FIELD_TYPE", "calendarDateField", "name", "string");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void create_timelineViewWithWrongResourceFieldType_fails() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(metaModelService.getModelFields("crm.lead")).thenReturn(List.of(
                field("start_at", "datetime"),
                field("payload", "json")
        ));
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        SavedViewCreateRequest timeline = createReq("personal", null);
        timeline.setViewType("timeline");
        ViewConfig config = new ViewConfig();
        config.setTimelineStartField("start_at");
        config.setTimelineResourceField("payload");
        timeline.setViewConfig(config);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(timeline));

        assertThat(ex.getMessage()).contains("INCOMPATIBLE_FIELD_TYPE", "timelineResourceField", "payload", "json");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void checkCapability_reportsMissingRequiredFieldsWithStableReasonCode() {
        SavedViewCapabilityCheckRequest request = new SavedViewCapabilityCheckRequest();
        request.setViewType("gallery");
        request.setViewConfig(new ViewConfig());

        SavedViewCapabilityCheckResponse response = service.checkCapability(request);

        assertEquals("gallery", response.getViewType());
        assertEquals("blocked", response.getStatus());
        assertThat(response.getMissingFields()).containsExactly("galleryImageField");
        assertThat(response.getReasons()).hasSize(1);
        assertThat(response.getReasons().get(0).getCode()).isEqualTo("MISSING_REQUIRED_FIELD");
        assertThat(response.getReasons().get(0).getField()).isEqualTo("galleryImageField");
    }

    @Test
    void checkCapability_blocksTimelineWithoutDateAndResourceMappings() {
        SavedViewCapabilityCheckRequest request = new SavedViewCapabilityCheckRequest();
        request.setViewType("timeline");
        request.setViewConfig(new ViewConfig());

        SavedViewCapabilityCheckResponse response = service.checkCapability(request);

        assertEquals("timeline", response.getViewType());
        assertEquals("blocked", response.getStatus());
        assertThat(response.getMissingFields()).containsExactly("timelineStartField", "timelineResourceField");
        assertThat(response.getReasons())
                .extracting(SavedViewCapabilityCheckResponse.Reason::getCode)
                .containsOnly("MISSING_REQUIRED_FIELD");
    }

    @Test
    void checkCapability_marksConfiguredAdvancedViewAvailable() {
        SavedViewCapabilityCheckRequest request = new SavedViewCapabilityCheckRequest();
        request.setViewType("tree");
        ViewConfig config = new ViewConfig();
        config.setTreeParentField("parent_id");
        request.setViewConfig(config);

        SavedViewCapabilityCheckResponse response = service.checkCapability(request);

        assertEquals("tree", response.getViewType());
        assertEquals("available", response.getStatus());
        assertThat(response.getReasons()).isEmpty();
        assertThat(response.getMissingFields()).isEmpty();
    }

    @Test
    void checkCapability_reportsSemanticValidationReasonsWhenModelCodeProvided() {
        when(metaModelService.getModelFields("crm.lead")).thenReturn(List.of(
                field("name", "string"),
                field("created_at", "datetime")
        ));

        SavedViewCapabilityCheckRequest request = new SavedViewCapabilityCheckRequest();
        request.setModelCode("crm.lead");
        request.setViewType("calendar");
        ViewConfig config = new ViewConfig();
        config.setCalendarDateField("name");
        request.setViewConfig(config);

        SavedViewCapabilityCheckResponse response = service.checkCapability(request);

        assertEquals("calendar", response.getViewType());
        assertEquals("blocked", response.getStatus());
        assertThat(response.getMissingFields()).isEmpty();
        assertThat(response.getReasons()).hasSize(1);
        assertThat(response.getReasons().get(0).getCode()).isEqualTo("INCOMPATIBLE_FIELD_TYPE");
        assertThat(response.getReasons().get(0).getField()).isEqualTo("calendarDateField");
        assertThat(response.getReasons().get(0).getMessage()).contains("name", "string");
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
        v.setIsImplicit(true);
        when(savedViewMapper.findByPid("p1")).thenReturn(v);
        SavedViewDTO dto = service.findByPid("p1");
        assertEquals("p1", dto.getPid());
        assertTrue(Boolean.TRUE.equals(dto.getIsImplicit()));
        assertEquals("manage", dto.getEffectivePermission());
        assertThat(dto.getActions()).contains("view", "copy", "save", "manage", "delete", "setDefault");
        assertFalse(Boolean.TRUE.equals(dto.getDirty()));
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
        SavedViewDTO dto = service.findByPid("g1");
        assertNotNull(dto);
        assertEquals("view", dto.getEffectivePermission());
        assertThat(dto.getActions()).containsExactly("view", "copy");
    }

    @Test
    void findByPid_teamMemberWithoutManagePermission_cannotSaveSharedView() {
        SavedView v = new SavedView();
        v.setPid("team1");
        v.setScope("team");
        v.setTeamId("teamA");
        v.setCreatedBy("owner_pid");
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        when(userPermissionService.hasPermission(7L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(false);
        when(userPermissionService.hasPermission(7L, MetaPermission.VIEW_MANAGE)).thenReturn(false);

        SavedViewDTO dto = service.findByPid("team1");

        assertEquals("view", dto.getEffectivePermission());
        assertThat(dto.getActions()).containsExactly("view", "copy");
    }

    @Test
    void findByPid_teamCreatorNoLongerMember_forbidden() {
        SavedView v = new SavedView();
        v.setPid("team1");
        v.setScope("team");
        v.setTeamId("teamA");
        v.setCreatedBy("user_pid");
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamB"));

        ValidationException ex = assertThrows(ValidationException.class, () -> service.findByPid("team1"));

        assertThat(ex.getMessage()).contains("not a member");
    }

    @Test
    void findByPid_teamMemberWithManagePermission_canSaveSharedView() {
        SavedView v = new SavedView();
        v.setPid("team1");
        v.setScope("team");
        v.setTeamId("teamA");
        v.setCreatedBy("owner_pid");
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        when(userPermissionService.hasPermission(7L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(true);

        SavedViewDTO dto = service.findByPid("team1");

        assertEquals("manage", dto.getEffectivePermission());
        assertThat(dto.getActions()).contains("view", "copy", "save", "manage", "delete", "setDefault", "share");
    }

    @Test
    void findByPid_teamSaveCollaborator_canSaveButCannotManageSharedView() {
        SavedView v = new SavedView();
        v.setPid("team1");
        v.setScope("team");
        v.setTeamId("teamA");
        v.setCreatedBy("owner_pid");
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder()
                .collaborators(List.of(ViewConfig.CollaboratorAcl.builder()
                        .principalType("user")
                        .principalPid("user_pid")
                        .permission("save")
                        .build()))
                .build());
        v.setViewConfig(config);
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        when(userPermissionService.hasPermission(7L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(false);
        when(userPermissionService.hasPermission(7L, MetaPermission.VIEW_MANAGE)).thenReturn(false);

        SavedViewDTO dto = service.findByPid("team1");

        assertEquals("save", dto.getEffectivePermission());
        assertThat(dto.getActions()).contains("view", "copy", "save", "setDefault");
        assertThat(dto.getActions()).doesNotContain("manage", "delete", "share");
    }

    @Test
    void update_teamSaveCollaboratorCanUpdateConfigButCannotRename() {
        SavedView v = new SavedView();
        v.setId(21L);
        v.setPid("team1");
        v.setTenantId(100L);
        v.setScope("team");
        v.setTeamId("teamA");
        v.setCreatedBy("owner_pid");
        v.setName("Team View");
        v.setModelCode("m");
        v.setPageKey("k");
        v.setViewType("table");
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder()
                .collaborators(List.of(ViewConfig.CollaboratorAcl.builder()
                        .principalType("user")
                        .principalPid("user_pid")
                        .permission("save")
                        .build()))
                .build());
        v.setViewConfig(config);
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        when(userPermissionService.hasPermission(7L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(false);
        when(userPermissionService.hasPermission(7L, MetaPermission.VIEW_MANAGE)).thenReturn(false);

        SavedViewUpdateRequest configUpdate = new SavedViewUpdateRequest();
        ViewConfig updatedConfig = new ViewConfig();
        updatedConfig.setRowHeight("tall");
        updatedConfig.setMeta(ViewConfig.Meta.builder()
                .managedBy("plugin")
                .locked(true)
                .collaborators(List.of(ViewConfig.CollaboratorAcl.builder()
                        .principalType("user")
                        .principalPid("user_pid")
                        .permission("manage")
                        .build()))
                .build());
        configUpdate.setViewConfig(updatedConfig);

        service.update("team1", configUpdate);
        ArgumentCaptor<SavedView> updateCaptor = ArgumentCaptor.forClass(SavedView.class);
        verify(savedViewMapper).updateSavedView(updateCaptor.capture());
        SavedView saved = updateCaptor.getValue();
        assertThat(saved.getViewConfig().getRowHeight()).isEqualTo("tall");
        assertThat(saved.getViewConfig().getMeta()).isSameAs(config.getMeta());
        assertThat(saved.getViewConfig().getMeta().getCollaborators())
                .extracting(ViewConfig.CollaboratorAcl::getPermission)
                .containsExactly("save");

        SavedViewUpdateRequest rename = new SavedViewUpdateRequest();
        rename.setName("Renamed");
        ValidationException ex = assertThrows(ValidationException.class, () -> service.update("team1", rename));
        assertThat(ex.getMessage()).contains("manage");
    }

    @Test
    void update_teamManageRejectsInvalidCollaboratorPermission() {
        SavedView v = teamManageView();
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        when(userService.findInTenantByPid(100L, "target_pid")).thenReturn(user("target_pid"));

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder()
                .collaborators(List.of(collaborator("user", "target_pid", "owner")))
                .build());
        req.setViewConfig(config);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.update("team1", req));

        assertThat(ex.getMessage()).contains("Invalid collaborator permission", "owner");
        verify(savedViewMapper, never()).updateSavedView(any());
    }

    @Test
    void update_teamManageRejectsUnsupportedCollaboratorPrincipalType() {
        SavedView v = teamManageView();
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder()
                .collaborators(List.of(collaborator("team", "team-a", "save")))
                .build());
        req.setViewConfig(config);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.update("team1", req));

        assertThat(ex.getMessage()).contains("Unsupported collaborator principalType", "team");
        verify(savedViewMapper, never()).updateSavedView(any());
    }

    @Test
    void update_teamManageRejectsBlankCollaboratorPrincipalPid() {
        SavedView v = teamManageView();
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder()
                .collaborators(List.of(collaborator("user", " ", "save")))
                .build());
        req.setViewConfig(config);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.update("team1", req));

        assertThat(ex.getMessage()).contains("Collaborator principalPid is required");
        verify(savedViewMapper, never()).updateSavedView(any());
    }

    @Test
    void update_teamManageRejectsCollaboratorOutsideCurrentTenant() {
        SavedView v = teamManageView();
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        when(userService.findInTenantByPid(100L, "missing_pid")).thenReturn(null);

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder()
                .collaborators(List.of(collaborator("user", "missing_pid", "save")))
                .build());
        req.setViewConfig(config);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.update("team1", req));

        assertThat(ex.getMessage()).contains("Collaborator user not found", "missing_pid");
        verify(savedViewMapper, never()).updateSavedView(any());
    }

    @Test
    void update_teamManageAcceptsValidatedCollaboratorAclAndAuditsCollaborators() {
        SavedView v = teamManageView();
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        when(userService.findInTenantByPid(100L, "target_pid")).thenReturn(user("target_pid"));

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder()
                .collaborators(List.of(collaborator("user", "target_pid", "save")))
                .build());
        req.setViewConfig(config);

        service.update("team1", req);

        ArgumentCaptor<SavedView> updateCaptor = ArgumentCaptor.forClass(SavedView.class);
        verify(savedViewMapper).updateSavedView(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getViewConfig().getMeta().getCollaborators())
                .extracting(ViewConfig.CollaboratorAcl::getPrincipalPid, ViewConfig.CollaboratorAcl::getPermission)
                .containsExactly(tuple("target_pid", "save"));

        ArgumentCaptor<AuditTrailEvent> auditCaptor = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(auditTrailService).recordAudit(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getChangedFields()).contains("collaborators", "viewConfig");
        assertThat(auditCaptor.getValue().getMetadata().get("summary").asText()).contains("collaborators");
    }

    @Test
    void findByPid_lockedPluginPreset_exposesViewAndCopyOnly() {
        SavedView v = new SavedView();
        v.setPid("preset1");
        v.setScope("global");
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder()
                .managedBy("plugin")
                .locked(true)
                .allowUserCopy(true)
                .build());
        v.setViewConfig(config);
        when(savedViewMapper.findByPid("preset1")).thenReturn(v);

        SavedViewDTO dto = service.findByPid("preset1");

        assertEquals("view", dto.getEffectivePermission());
        assertThat(dto.getActions()).containsExactly("view", "copy");
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

    private FieldDefinition field(String code, String dataType) {
        return FieldDefinition.builder()
                .code(code)
                .name(code)
                .displayName(code)
                .dataType(dataType)
                .build();
    }

    private SavedView teamManageView() {
        SavedView v = new SavedView();
        v.setId(21L);
        v.setPid("team1");
        v.setTenantId(100L);
        v.setScope("team");
        v.setTeamId("teamA");
        v.setCreatedBy("user_pid");
        v.setOwnerId("user_pid");
        v.setName("Team View");
        v.setModelCode("m");
        v.setPageKey("k");
        v.setViewType("table");
        v.setViewConfig(new ViewConfig());
        return v;
    }

    private ViewConfig.CollaboratorAcl collaborator(String principalType, String principalPid, String permission) {
        return ViewConfig.CollaboratorAcl.builder()
                .principalType(principalType)
                .principalPid(principalPid)
                .permission(permission)
                .build();
    }

    private UserSearchDTO user(String pid) {
        return UserSearchDTO.builder()
                .pid(pid)
                .displayName(pid)
                .build();
    }

    @Test
    void update_advancedViewCannotDropRequiredConfig() {
        SavedView v = new SavedView();
        v.setPid("p1"); v.setScope("personal"); v.setOwnerId("user_pid");
        v.setName("Timeline"); v.setModelCode("m"); v.setPageKey("k"); v.setViewType("calendar");
        when(savedViewMapper.findByPid("p1")).thenReturn(v);

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        req.setViewConfig(new ViewConfig());

        ValidationException ex = assertThrows(ValidationException.class, () -> service.update("p1", req));
        assertThat(ex.getMessage()).contains("calendarDateField");
        verify(savedViewMapper, never()).updateSavedView(any(SavedView.class));
    }

    @Test
    void update_galleryViewCannotDropImageField() {
        SavedView v = new SavedView();
        v.setPid("p1"); v.setScope("personal"); v.setOwnerId("user_pid");
        v.setName("Gallery"); v.setModelCode("m"); v.setPageKey("k"); v.setViewType("gallery");
        when(savedViewMapper.findByPid("p1")).thenReturn(v);

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        ViewConfig partial = new ViewConfig();
        partial.setGalleryTitleField("name");
        req.setViewConfig(partial);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.update("p1", req));
        assertThat(ex.getMessage()).contains("galleryImageField");
        verify(savedViewMapper, never()).updateSavedView(any(SavedView.class));
    }

    @Test
    void update_treeViewCannotDropParentField() {
        SavedView v = new SavedView();
        v.setPid("p1"); v.setScope("personal"); v.setOwnerId("user_pid");
        v.setName("Tree"); v.setModelCode("m"); v.setPageKey("k"); v.setViewType("tree");
        when(savedViewMapper.findByPid("p1")).thenReturn(v);

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        ViewConfig partial = new ViewConfig();
        partial.setTreeTitleField("name");
        req.setViewConfig(partial);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.update("p1", req));
        assertThat(ex.getMessage()).contains("treeParentField");
        verify(savedViewMapper, never()).updateSavedView(any(SavedView.class));
    }

    @Test
    void update_lockedPluginPreset_forbidden() {
        SavedView v = new SavedView();
        v.setPid("plugin1");
        v.setScope("global");
        v.setCreatedBy("user_pid");
        v.setName("Plugin View");
        v.setModelCode("m");
        v.setPageKey("k");
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder()
                .viewKey("crm.plugin.view")
                .managedBy("plugin")
                .locked(true)
                .allowUserCopy(true)
                .build());
        v.setViewConfig(config);
        when(savedViewMapper.findByPid("plugin1")).thenReturn(v);

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        req.setName("Renamed");

        ValidationException ex = assertThrows(ValidationException.class, () -> service.update("plugin1", req));
        assertThat(ex.getMessage()).contains("managed by a plugin");
        verify(savedViewMapper, never()).updateSavedView(any(SavedView.class));
    }

    @Test
    void update_teamView_recordsSharedAudit() {
        SavedView v = new SavedView();
        v.setId(21L);
        v.setPid("team1");
        v.setTenantId(100L);
        v.setScope("team");
        v.setTeamId("teamA");
        v.setCreatedBy("user_pid");
        v.setOwnerId("user_pid");
        v.setName("Team View");
        v.setModelCode("m");
        v.setPageKey("k");
        v.setViewType("table");
        ViewConfig oldConfig = new ViewConfig();
        oldConfig.setRowHeight("short");
        v.setViewConfig(oldConfig);
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        ViewConfig newConfig = new ViewConfig();
        newConfig.setRowHeight("tall");
        req.setViewConfig(newConfig);

        service.update("team1", req);

        ArgumentCaptor<AuditTrailEvent> captor = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(auditTrailService).recordAudit(captor.capture());
        AuditTrailEvent event = captor.getValue();
        assertEquals("SAVED_VIEW", event.getEventType());
        assertEquals("saved_view", event.getEntityType());
        assertEquals("team1", event.getEntityPid());
        assertEquals("UPDATE", event.getOperationType());
        assertThat(event.getChangedFields()).contains("viewConfig");
        assertThat(event.getMetadata().get("scope").asText()).isEqualTo("team");
        assertThat(event.getMetadata().get("summary").asText()).contains("configuration");
    }

    @Test
    void update_personalView_doesNotRecordSharedAudit() {
        SavedView v = new SavedView();
        v.setPid("p1"); v.setScope("personal"); v.setOwnerId("user_pid");
        v.setName("Old"); v.setModelCode("m"); v.setPageKey("k");
        when(savedViewMapper.findByPid("p1")).thenReturn(v);
        when(savedViewMapper.countByNameForUser(eq("m"), eq("k"), eq("New"), eq("user_pid"), eq("p1")))
                .thenReturn(0);

        SavedViewUpdateRequest req = new SavedViewUpdateRequest();
        req.setName("New");
        service.update("p1", req);

        verify(auditTrailService, never()).recordAudit(any());
    }

    @Test
    void getAuditEvents_validatesReadAccessAndQueriesSavedViewTrail() {
        SavedView v = new SavedView();
        v.setPid("team1");
        v.setScope("team");
        v.setTeamId("teamA");
        v.setTenantId(100L);
        v.setModelCode("m");
        v.setPageKey("k");
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        AuditTrail trail = new AuditTrail();
        trail.setEntityPid("team1");
        when(auditTrailService.getAuditTrailByPid(100L, "saved_view", "team1"))
                .thenReturn(List.of(trail));

        List<SavedViewAuditEventDTO> result = service.getAuditEvents("team1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEntityPid()).isEqualTo("team1");
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
    void delete_teamView_nonMemberWithManagePermission_forbidden() {
        SavedView v = new SavedView();
        v.setId(21L);
        v.setPid("team1");
        v.setScope("team");
        v.setTeamId("teamA");
        v.setCreatedBy("owner_pid");
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamB"));
        when(userPermissionService.hasPermission(7L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(true);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.delete("team1"));

        assertThat(ex.getMessage()).contains("not a member");
        verify(savedViewMapper, never()).deleteById(anyLong());
    }

    @Test
    void setAsDefault_teamView_nonMemberWithManagePermission_forbidden() {
        SavedView v = new SavedView();
        v.setPid("team1");
        v.setScope("team");
        v.setTeamId("teamA");
        v.setCreatedBy("owner_pid");
        v.setModelCode("m");
        v.setPageKey("k");
        when(savedViewMapper.findByPid("team1")).thenReturn(v);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamB"));
        when(userPermissionService.hasPermission(7L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(true);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.setAsDefault("team1"));

        assertThat(ex.getMessage()).contains("not a member");
        verify(savedViewMapper, never()).clearTeamDefaultFlag(any(), any(), any());
        verify(savedViewMapper, never()).updateSavedView(any());
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
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("k")).thenReturn(page);

        SavedViewDTO dto = service.duplicate("g1", "Dup");

        assertEquals("personal", dto.getScope());
    }

    @Test
    void copyToPersonal_fromTeamView_createsPersonalCopyWithOptionalConfigOverride() {
        SavedView src = new SavedView();
        src.setPid("team1");
        src.setScope("team");
        src.setTeamId("teamA");
        src.setModelCode("m");
        src.setPageKey("k");
        src.setViewType("kanban");
        src.setAllowFullModel(true);
        src.setSortOrder(7);
        ViewConfig originalConfig = new ViewConfig();
        originalConfig.setGroupByField("status");
        originalConfig.setTitleField("name");
        originalConfig.setRowHeight("short");
        originalConfig.setMeta(ViewConfig.Meta.builder()
                .viewKey("team.plugin.view")
                .managedBy("plugin")
                .locked(true)
                .allowUserCopy(true)
                .build());
        src.setViewConfig(originalConfig);
        when(savedViewMapper.findByPid("team1")).thenReturn(src);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("teamA"));
        when(savedViewMapper.countByNameForUser(any(), any(), eq("My Team View"), eq("user_pid"), any()))
                .thenReturn(0);
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("k")).thenReturn(page);
        when(metaModelService.getModelFields("m")).thenReturn(List.of(
                field("status", "status"),
                field("name", "string")
        ));

        ViewConfig overrideConfig = new ViewConfig();
        overrideConfig.setRowHeight("tall");
        SavedViewDTO dto = service.copyToPersonal("team1", "My Team View", overrideConfig);

        assertEquals("personal", dto.getScope());
        assertNull(dto.getTeamId());
        assertEquals("user_pid", dto.getOwnerId());
        assertEquals("kanban", dto.getViewType());
        assertEquals("status", dto.getViewConfig().getGroupByField());
        assertEquals("name", dto.getViewConfig().getTitleField());
        assertEquals("tall", dto.getViewConfig().getRowHeight());
        assertFalse(Boolean.TRUE.equals(dto.getIsDefault()));

        ArgumentCaptor<SavedView> captor = ArgumentCaptor.forClass(SavedView.class);
        verify(savedViewMapper).insertSavedView(captor.capture());
        SavedView inserted = captor.getValue();
        assertEquals("personal", inserted.getScope());
        assertNull(inserted.getTeamId());
        assertEquals("user_pid", inserted.getOwnerId());
        assertEquals("tall", inserted.getViewConfig().getRowHeight());
        assertEquals("user", inserted.getViewConfig().getMeta().getManagedBy());
        assertFalse(Boolean.TRUE.equals(inserted.getViewConfig().getMeta().getLocked()));
        assertEquals("team1", inserted.getViewConfig().getMeta().getOriginViewPid());
        assertNull(inserted.getViewConfig().getMeta().getViewKey());
    }

    @Test
    void copyToPersonal_atPersonalLimit_failsBeforeInsert() {
        SavedView src = new SavedView();
        src.setPid("global1");
        src.setScope("global");
        src.setModelCode("m");
        src.setPageKey("k");
        src.setViewType("table");
        src.setViewConfig(new ViewConfig());
        when(savedViewMapper.findByPid("global1")).thenReturn(src);
        when(savedViewMapper.countByNameForUser(eq("m"), eq("k"), eq("My Copy"), eq("user_pid"), isNull()))
                .thenReturn(0);
        when(savedViewMapper.countActiveNonImplicitViewsForScope(
                eq("m"), eq("k"), eq("personal"), eq("user_pid"), isNull()))
                .thenReturn(10);
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("k")).thenReturn(page);

        ValidationException ex = assertThrows(
                ValidationException.class,
                () -> service.copyToPersonal("global1", "My Copy", null));

        assertThat(ex.getMessage()).contains("limit").contains("10");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void copyToPersonal_disallowedWhenPresetBlocksCopy() {
        SavedView src = new SavedView();
        src.setPid("plugin1");
        src.setScope("global");
        src.setModelCode("m");
        src.setPageKey("k");
        src.setViewType("table");
        ViewConfig config = new ViewConfig();
        config.setMeta(ViewConfig.Meta.builder()
                .viewKey("crm.plugin.view")
                .managedBy("plugin")
                .locked(true)
                .allowUserCopy(false)
                .build());
        src.setViewConfig(config);
        when(savedViewMapper.findByPid("plugin1")).thenReturn(src);

        ValidationException ex = assertThrows(
                ValidationException.class,
                () -> service.copyToPersonal("plugin1", "My Copy", null));

        assertThat(ex.getMessage()).contains("cannot be copied");
        verify(savedViewMapper, never()).insertSavedView(any());
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
        verify(savedViewMapper, never()).countActiveNonImplicitViewsForScope(any(), any(), any(), any(), any());
    }

    @Test
    void isNameUnique_delegatesToMapper() {
        when(savedViewMapper.countByNameForUser("m", "k", "n", "user_pid", null)).thenReturn(0);
        assertTrue(service.isNameUnique("m", "k", "n", null));
        when(savedViewMapper.countByNameForUser("m", "k", "n", "user_pid", null)).thenReturn(1);
        assertFalse(service.isNameUnique("m", "k", "n", null));
    }

    // ---- pageKey validation tests ----

    @Test
    void create_withNonExistentPageKey_throwsValidationError() {
        // Arrange: pageSchemaMapper returns null → page does not exist
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(null);

        SavedViewCreateRequest req = createReq("personal", null);

        // Act + Assert: must throw before reaching insertSavedView
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.create(req));

        assertTrue(ex.getMessage().contains("[S-SAVED-VIEW]"),
                "Error must carry the [S-SAVED-VIEW] error code prefix");
        assertTrue(ex.getMessage().contains("crm/leads"),
                "Error must name the offending pageKey");
        verify(savedViewMapper, never()).insertSavedView(any());
    }

    @Test
    void create_withValidPageKey_succeeds() {
        // Arrange: pageSchemaMapper returns a real page → validation passes
        PageSchema existingPage = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(existingPage);
        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        // Act
        SavedViewDTO dto = service.create(createReq("personal", null));

        // Assert: happy path completes
        assertNotNull(dto);
        assertEquals("My View", dto.getName());
        verify(savedViewMapper).insertSavedView(any(SavedView.class));
    }

    @Test
    void create_withNullPageKey_skipsPageKeyValidation() {
        // A request with no pageKey should skip the pageKey check entirely
        SavedViewCreateRequest req = new SavedViewCreateRequest();
        req.setName("No PageKey View");
        req.setModelCode("crm.lead");
        // pageKey intentionally null
        req.setScope("personal");
        req.setViewType("table");
        req.setViewConfig(new ViewConfig());
        req.setIsDefault(false);

        when(savedViewMapper.countByNameForUser(anyString(), anyString(), anyString(), anyString(), isNull()))
                .thenReturn(0);

        // Should not call pageSchemaMapper at all
        SavedViewDTO dto = service.create(req);

        assertNotNull(dto);
        verify(pageSchemaMapper, never()).selectAnyByPageKey(any());
    }

    @Test
    void teamScope_dtoPopulatesTeamName() {
        PageSchema page = new PageSchema();
        when(pageSchemaMapper.selectAnyByPageKey("crm/leads")).thenReturn(page);
        when(savedViewMapper.countByNameForUser(any(), any(), any(), any(), any())).thenReturn(0);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("tA"));
        Team team = new Team(); team.setName("Team A");
        when(teamMapper.findByPid("tA")).thenReturn(team);

        SavedViewCreateRequest req = createReq("team", "tA");
        SavedViewDTO dto = service.create(req);
        assertEquals("Team A", dto.getTeamName());
    }
}
