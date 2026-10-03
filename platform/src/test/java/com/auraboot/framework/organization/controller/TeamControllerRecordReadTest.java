package com.auraboot.framework.organization.controller;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.TeamResponse;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.organization.service.TeamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamControllerRecordReadTest {
    @Mock private DynamicDataService dynamicDataService;
    @Mock private TeamService teamService;
    @Mock private TeamMemberService teamMemberService;
    @InjectMocks private TeamController controller;

    @Test
    void detailEvaluatesTheModelRecordBeforeReturningNativeData() {
        when(dynamicDataService.getById("ab_team", "team-1")).thenReturn(Map.of("pid", "team-1"));
        when(teamService.getTeamByPid("team-1")).thenReturn(new TeamResponse());
        controller.getTeam("team-1");
        var order = inOrder(dynamicDataService, teamService);
        order.verify(dynamicDataService).getById("ab_team", "team-1");
        order.verify(teamService).getTeamByPid("team-1");
    }

    @Test
    void detailDoesNotReturnNativeDataWhenRecordReadIsDenied() {
        when(dynamicDataService.getById("ab_team", "team-1"))
                .thenThrow(new AccessDeniedException("Record scope denied"));
        assertThatThrownBy(() -> controller.getTeam("team-1")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(teamService, teamMemberService);
    }

    @Test
    void membershipEvaluatesTheModelRecordBeforeReturningMembers() {
        when(dynamicDataService.getById("ab_team", "team-1")).thenReturn(Map.of("pid", "team-1"));
        when(teamMemberService.listMembers("team-1")).thenReturn(List.of());
        controller.listMembers("team-1");
        var order = inOrder(dynamicDataService, teamMemberService);
        order.verify(dynamicDataService).getById("ab_team", "team-1");
        order.verify(teamMemberService).listMembers("team-1");
    }

    @Test
    void membershipDoesNotReturnMembersWhenRecordReadIsDenied() {
        when(dynamicDataService.getById("ab_team", "team-1"))
                .thenThrow(new AccessDeniedException("Record scope denied"));
        assertThatThrownBy(() -> controller.listMembers("team-1"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(teamService, teamMemberService);
    }

    @Test
    void detailDoesNotFallBackToNativeDataWhenModelRecordIsAbsent() {
        when(dynamicDataService.getById("ab_team", "team-1")).thenReturn(null);
        assertThatThrownBy(() -> controller.getTeam("team-1")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(teamService, teamMemberService);
    }

    @Test
    void membershipDoesNotFallBackToNativeDataWhenModelRecordIsAbsent() {
        when(dynamicDataService.getById("ab_team", "team-1")).thenReturn(null);
        assertThatThrownBy(() -> controller.listMembers("team-1"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(teamService, teamMemberService);
    }
}
