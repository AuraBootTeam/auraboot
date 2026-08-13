package com.auraboot.framework.organization.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.entity.TeamMember;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.organization.mapper.TeamMemberMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamGovernanceServiceTest {

    @Mock private TeamMapper teamMapper;
    @Mock private TeamMemberMapper teamMemberMapper;
    @Mock private TenantMemberService tenantMemberService;
    @Mock private UserService userService;
    private TeamGovernanceService service;
    private Team team;

    @BeforeEach
    void setUp() {
        service = new TeamGovernanceService(teamMapper, teamMemberMapper, tenantMemberService, userService);
        team = new Team();
        team.setId(11L);
        team.setPid("team-11");
        team.setTenantId(7L);
    }

    @Test
    void requireTeamUsesTenantScopedPid() {
        when(teamMapper.findByTenantIdAndPid(7L, "team-11")).thenReturn(team);
        assertThat(service.requireTeam(7L, "team-11")).isSameAs(team);
        assertThatThrownBy(() -> service.requireTeam(8L, "team-11"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void synchronizeLeaderRequiresActiveTenantMemberAndCreatesMembership() {
        User leader = new User();
        leader.setId(21L);
        leader.setPid("user-21");
        TenantMember tenantMember = new TenantMember();
        tenantMember.setStatus("active");
        when(userService.findByPid("user-21")).thenReturn(leader);
        when(tenantMemberService.findByTenantIdAndUserId(7L, 21L)).thenReturn(tenantMember);

        service.synchronizeLeader(team, "user-21", 99L);

        ArgumentCaptor<TeamMember> saved = ArgumentCaptor.forClass(TeamMember.class);
        verify(teamMemberMapper).insert(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(TeamGovernanceService.LEADER);
        assertThat(saved.getValue().getTenantId()).isEqualTo(7L);
        assertThat(team.getLeaderId()).isEqualTo("user-21");
    }

    @Test
    void synchronizeLeaderRejectsInactiveOrCrossTenantUser() {
        User leader = new User();
        leader.setId(21L);
        leader.setPid("user-21");
        when(userService.findByPid("user-21")).thenReturn(leader);
        when(tenantMemberService.findByTenantIdAndUserId(7L, 21L)).thenReturn(null);

        assertThatThrownBy(() -> service.synchronizeLeader(team, "user-21", 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("same tenant");
    }

    @Test
    void synchronizeLeaderDemotesPreviousAndPromotesExistingMember() {
        team.setLeaderId("old-user");
        User oldUser = new User(); oldUser.setId(20L); oldUser.setPid("old-user");
        User newUser = new User(); newUser.setId(21L); newUser.setPid("new-user");
        TenantMember active = new TenantMember(); active.setStatus("active");
        TeamMember oldMembership = new TeamMember(); oldMembership.setId(30L);
        TeamMember newMembership = new TeamMember(); newMembership.setId(31L); newMembership.setRole("MEMBER");
        when(userService.findByPid("old-user")).thenReturn(oldUser);
        when(userService.findByPid("new-user")).thenReturn(newUser);
        when(tenantMemberService.findByTenantIdAndUserId(7L, 21L)).thenReturn(active);
        when(teamMemberMapper.findByTeamIdAndUserId(11L, 20L)).thenReturn(oldMembership);
        when(teamMemberMapper.findByTeamIdAndUserId(11L, 21L)).thenReturn(newMembership);

        service.synchronizeLeader(team, "new-user", 99L);

        verify(teamMemberMapper).updateRole(30L, 7L, TeamGovernanceService.MEMBER, 99L);
        verify(teamMemberMapper).updateRole(31L, 7L, TeamGovernanceService.LEADER, 99L);
        assertThat(team.getLeaderId()).isEqualTo("new-user");
    }

    @Test
    void leaderCannotBeRemovedOrAddedThroughGenericMembershipApi() {
        TeamMember leader = new TeamMember();
        leader.setRole("leader");
        assertThatThrownBy(() -> service.assertCanRemoveMembership(team, leader))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.assertMemberRoleCanBeAdded("LEADER"))
                .isInstanceOf(BusinessException.class);
    }
}
