package com.auraboot.framework.view;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.entity.TeamMember;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.organization.mapper.TeamMemberMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.view.dto.ChipPinDTO;
import com.auraboot.framework.view.service.SavedViewChipPinService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration tests for team-scoped quick-filter chip pins (M3).
 *
 * <p>Proves the cross-user visibility contract against a real DB: a team-manager
 * member pins a view for the team, every team member's effective-pins include it,
 * and a non-member's do not. Team-manage / membership denial is asserted too.
 *
 * <p>The chip-pin table is standalone (no FK / read-side join to {@code ab_saved_view}),
 * so a fabricated {@code viewPid} is enough to exercise the pin round-trip. The
 * {@link UserPermissionService} is mocked because a bare integration-test DB does
 * not register permission codes (per {@code RbacEnforcementMatrixIT}); real team
 * membership is resolved from the seeded {@code ab_team_member} rows.
 */
@Slf4j
@DisplayName("SavedView team chip pins (M3) integration tests")
class SavedViewChipPinTeamIT extends BaseIntegrationTest {

    private static final String MODEL_CODE = "e2et_order";
    private static final String PAGE_KEY = "e2et_order_list";

    @Autowired
    private SavedViewChipPinService chipPinService;

    @Autowired
    private TeamMapper teamMapper;

    @Autowired
    private TeamMemberMapper teamMemberMapper;

    @MockitoBean
    private UserPermissionService userPermissionService;

    private String teamPid;

    @BeforeEach
    void seedTeamAndMembership() {
        // testUser is a member of a freshly seeded team; the caller holds team-manage.
        Team team = new Team();
        team.setPid(UniqueIdGenerator.generate());
        team.setTenantId(testTenant.getId());
        team.setCode("chip_pin_team_" + System.nanoTime());
        team.setName("Chip Pin Test Team");
        team.setStatus("active");
        team.setDeletedFlag(false);
        team.setCreatedAt(Instant.now());
        team.setUpdatedAt(Instant.now());
        team.setCreatedBy(testUser.getId());
        team.setUpdatedBy(testUser.getId());
        teamMapper.insert(team);
        teamPid = team.getPid();

        TeamMember member = new TeamMember();
        member.setPid(UniqueIdGenerator.generate());
        member.setTenantId(testTenant.getId());
        member.setTeamId(team.getId());
        member.setUserId(testUser.getId());
        member.setRole("member");
        member.setJoinedAt(Instant.now());
        member.setCreatedAt(Instant.now());
        member.setUpdatedAt(Instant.now());
        member.setCreatedBy(testUser.getId());
        member.setUpdatedBy(testUser.getId());
        teamMemberMapper.insert(member);

        when(userPermissionService.hasPermission(anyLong(), eq(MetaPermission.VIEW_TEAM_MANAGE)))
                .thenReturn(true);
    }

    @Test
    @DisplayName("a team pin is visible to team members and hidden from non-members")
    void teamPinVisibleToMembersHiddenFromNonMembers() {
        String viewPid = "team-view-" + System.nanoTime();
        chipPinService.pinTeam(viewPid, teamPid, MODEL_CODE, PAGE_KEY, 1);

        // Member (testUser) sees the team pin.
        List<ChipPinDTO> memberPins = chipPinService.listEffectivePins(MODEL_CODE, PAGE_KEY);
        assertThat(memberPins).extracting(ChipPinDTO::viewPid).contains(viewPid);

        // A non-member of the team (different user, same tenant) does not.
        MetaContext.setContext(testTenant.getId(), 9_999_999L, "non-member-pid", "nonmember");
        try {
            List<ChipPinDTO> nonMemberPins = chipPinService.listEffectivePins(MODEL_CODE, PAGE_KEY);
            assertThat(nonMemberPins).extracting(ChipPinDTO::viewPid).doesNotContain(viewPid);
        } finally {
            applyTestMetaContext();
        }
    }

    @Test
    @DisplayName("unpinTeam removes the team pin for members")
    void unpinTeamRemovesPin() {
        String viewPid = "team-view-" + System.nanoTime();
        chipPinService.pinTeam(viewPid, teamPid, MODEL_CODE, PAGE_KEY, 1);
        assertThat(chipPinService.listEffectivePins(MODEL_CODE, PAGE_KEY))
                .extracting(ChipPinDTO::viewPid).contains(viewPid);

        chipPinService.unpinTeam(viewPid, teamPid);

        assertThat(chipPinService.listEffectivePins(MODEL_CODE, PAGE_KEY))
                .extracting(ChipPinDTO::viewPid).doesNotContain(viewPid);
    }

    @Test
    @DisplayName("pinning for a team the caller does not belong to is forbidden")
    void pinTeamForbiddenForNonMemberTeam() {
        String viewPid = "team-view-" + System.nanoTime();
        assertThatThrownBy(() ->
                chipPinService.pinTeam(viewPid, "team-i-am-not-in", MODEL_CODE, PAGE_KEY, 1))
                .hasMessageContaining("not a member");
    }

    @Test
    @DisplayName("pinning for a team without team-manage is forbidden")
    void pinTeamForbiddenWithoutTeamManage() {
        when(userPermissionService.hasPermission(anyLong(), eq(MetaPermission.VIEW_TEAM_MANAGE)))
                .thenReturn(false);

        String viewPid = "team-view-" + System.nanoTime();
        assertThatThrownBy(() ->
                chipPinService.pinTeam(viewPid, teamPid, MODEL_CODE, PAGE_KEY, 1))
                .hasMessageContaining("team-manage");
    }
}
