package com.auraboot.framework.view.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.view.dto.ChipPinDTO;
import com.auraboot.framework.view.entity.SavedViewChipPin;
import com.auraboot.framework.view.mapper.SavedViewChipPinMapper;
import com.auraboot.framework.view.service.impl.SavedViewChipPinServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewChipPinServiceTest {

    private static final String TEAM_ID = "team-42";

    @Mock
    private SavedViewChipPinMapper chipPinMapper;

    @Mock
    private CurrentUserTeamResolver currentUserTeamResolver;

    @Mock
    private UserPermissionService userPermissionService;

    @InjectMocks
    private SavedViewChipPinServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(1L, 100L, "user-pid-1", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("pinPersonal inserts a personal pin when none exists")
    void pinPersonalInsertsWhenAbsent() {
        when(chipPinMapper.selectOne(any())).thenReturn(null);

        service.pinPersonal("view-1", "e2et_order", "e2et_order_list", 3);

        ArgumentCaptor<SavedViewChipPin> captor = ArgumentCaptor.forClass(SavedViewChipPin.class);
        verify(chipPinMapper).insert(captor.capture());
        SavedViewChipPin pin = captor.getValue();
        assertThat(pin.getScope()).isEqualTo("personal");
        assertThat(pin.getUserId()).isEqualTo("user-pid-1");
        assertThat(pin.getTenantId()).isEqualTo(1L);
        assertThat(pin.getViewPid()).isEqualTo("view-1");
        assertThat(pin.getModelCode()).isEqualTo("e2et_order");
        assertThat(pin.getSortOrder()).isEqualTo(3);
        assertThat(pin.getPid()).isNotBlank();
        assertThat(pin.getCreatedBy()).isEqualTo("user-pid-1");
    }

    @Test
    @DisplayName("pinPersonal is idempotent — updates order instead of inserting a duplicate")
    void pinPersonalIsIdempotent() {
        SavedViewChipPin existing = new SavedViewChipPin();
        existing.setId(5L);
        existing.setSortOrder(1);
        when(chipPinMapper.selectOne(any())).thenReturn(existing);

        service.pinPersonal("view-1", "e2et_order", "e2et_order_list", 9);

        verify(chipPinMapper, never()).insert(any(SavedViewChipPin.class));
        ArgumentCaptor<SavedViewChipPin> updated = ArgumentCaptor.forClass(SavedViewChipPin.class);
        verify(chipPinMapper).updateById(updated.capture());
        assertThat(updated.getValue().getSortOrder()).isEqualTo(9);
    }

    @Test
    @DisplayName("unpinPersonal deletes the current user's pin")
    void unpinPersonalDeletes() {
        service.unpinPersonal("view-1");
        verify(chipPinMapper).delete(any());
    }

    @Test
    @DisplayName("listEffectivePins maps rows to {viewPid, order}")
    void listEffectivePinsMapsRows() {
        SavedViewChipPin p = new SavedViewChipPin();
        p.setViewPid("view-1");
        p.setSortOrder(2);
        when(chipPinMapper.selectList(any())).thenReturn(List.of(p));

        List<ChipPinDTO> pins = service.listEffectivePins("e2et_order", "e2et_order_list");

        assertThat(pins).hasSize(1);
        assertThat(pins.get(0).viewPid()).isEqualTo("view-1");
        assertThat(pins.get(0).order()).isEqualTo(2);
    }

    @Test
    @DisplayName("pinPersonal fails fast without a user in context")
    void pinPersonalRequiresUser() {
        MetaContext.clear();
        assertThatThrownBy(() -> service.pinPersonal("view-1", "e2et_order", "e2et_order_list", 1))
                .isInstanceOf(IllegalStateException.class);
    }

    // ==================== team pins (M3) ====================

    @Test
    @DisplayName("pinTeam inserts a team pin for a member holding team-manage")
    void pinTeamInsertsForAuthorizedMember() {
        when(userPermissionService.hasPermission(100L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(true);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of(TEAM_ID));
        when(chipPinMapper.selectOne(any())).thenReturn(null);

        service.pinTeam("view-1", TEAM_ID, "e2et_order", "e2et_order_list", 2);

        ArgumentCaptor<SavedViewChipPin> captor = ArgumentCaptor.forClass(SavedViewChipPin.class);
        verify(chipPinMapper).insert(captor.capture());
        SavedViewChipPin pin = captor.getValue();
        assertThat(pin.getScope()).isEqualTo("team");
        assertThat(pin.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(pin.getUserId()).isNull();
        assertThat(pin.getViewPid()).isEqualTo("view-1");
        assertThat(pin.getSortOrder()).isEqualTo(2);
        assertThat(pin.getCreatedBy()).isEqualTo("user-pid-1");
    }

    @Test
    @DisplayName("pinTeam is idempotent — updates order instead of inserting a duplicate")
    void pinTeamIsIdempotent() {
        when(userPermissionService.hasPermission(100L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(true);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of(TEAM_ID));
        SavedViewChipPin existing = new SavedViewChipPin();
        existing.setId(7L);
        existing.setSortOrder(1);
        when(chipPinMapper.selectOne(any())).thenReturn(existing);

        service.pinTeam("view-1", TEAM_ID, "e2et_order", "e2et_order_list", 4);

        verify(chipPinMapper, never()).insert(any(SavedViewChipPin.class));
        ArgumentCaptor<SavedViewChipPin> updated = ArgumentCaptor.forClass(SavedViewChipPin.class);
        verify(chipPinMapper).updateById(updated.capture());
        assertThat(updated.getValue().getSortOrder()).isEqualTo(4);
    }

    @Test
    @DisplayName("pinTeam is forbidden without team-manage permission")
    void pinTeamForbiddenWithoutTeamManage() {
        when(userPermissionService.hasPermission(100L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> service.pinTeam("view-1", TEAM_ID, "e2et_order", "e2et_order_list", 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("team-manage");
        verify(chipPinMapper, never()).insert(any(SavedViewChipPin.class));
    }

    @Test
    @DisplayName("pinTeam is forbidden when the caller is not a member of the team")
    void pinTeamForbiddenForNonMember() {
        when(userPermissionService.hasPermission(100L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(true);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of("other-team"));

        assertThatThrownBy(() -> service.pinTeam("view-1", TEAM_ID, "e2et_order", "e2et_order_list", 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not a member");
        verify(chipPinMapper, never()).insert(any(SavedViewChipPin.class));
    }

    @Test
    @DisplayName("pinTeam rejects a blank teamId")
    void pinTeamRejectsBlankTeamId() {
        assertThatThrownBy(() -> service.pinTeam("view-1", "  ", "e2et_order", "e2et_order_list", 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("teamId is required");
        verify(chipPinMapper, never()).insert(any(SavedViewChipPin.class));
    }

    @Test
    @DisplayName("unpinTeam deletes the team pin for an authorized member")
    void unpinTeamDeletes() {
        when(userPermissionService.hasPermission(100L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(true);
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of(TEAM_ID));

        service.unpinTeam("view-1", TEAM_ID);

        verify(chipPinMapper).delete(any());
    }

    @Test
    @DisplayName("unpinTeam is forbidden without team-manage permission")
    void unpinTeamForbiddenWithoutTeamManage() {
        when(userPermissionService.hasPermission(100L, MetaPermission.VIEW_TEAM_MANAGE)).thenReturn(false);

        assertThatThrownBy(() -> service.unpinTeam("view-1", TEAM_ID))
                .isInstanceOf(ValidationException.class);
        verify(chipPinMapper, never()).delete(any());
    }

    @Test
    @DisplayName("listEffectivePins unions personal + team pins and de-dupes by viewPid")
    void listEffectivePinsUnionsAndDedupes() {
        when(currentUserTeamResolver.resolveCurrentUserTeamIds()).thenReturn(List.of(TEAM_ID));

        SavedViewChipPin personal = new SavedViewChipPin();
        personal.setViewPid("view-1");
        personal.setSortOrder(2);

        SavedViewChipPin teamDup = new SavedViewChipPin(); // same view as personal
        teamDup.setViewPid("view-1");
        teamDup.setSortOrder(5);
        SavedViewChipPin teamOnly = new SavedViewChipPin();
        teamOnly.setViewPid("view-2");
        teamOnly.setSortOrder(3);

        // First selectList = personal query, second = team query.
        when(chipPinMapper.selectList(any()))
                .thenReturn(List.of(personal))
                .thenReturn(List.of(teamDup, teamOnly));

        List<ChipPinDTO> pins = service.listEffectivePins("e2et_order", "e2et_order_list");

        assertThat(pins).hasSize(2);
        assertThat(pins).extracting(ChipPinDTO::viewPid).containsExactly("view-1", "view-2");
        // Personal pin is added first, so its order wins the de-dup on view-1.
        assertThat(pins.get(0).order()).isEqualTo(2);
        assertThat(pins.get(1).order()).isEqualTo(3);
    }
}
