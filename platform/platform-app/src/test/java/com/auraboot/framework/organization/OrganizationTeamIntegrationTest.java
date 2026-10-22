package com.auraboot.framework.organization;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.organization.dto.*;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.organization.service.TeamService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for TeamService and TeamMemberService.
 * Tests team CRUD lifecycle and member management.
 * Uses real database, no mocking. Data persists (no rollback).
 */
@Slf4j
@DisplayName("Organization Team Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class OrganizationTeamIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TeamService teamService;

    @Autowired
    private TeamMemberService teamMemberService;

    private final String runId = "t-" + System.currentTimeMillis();

    // Cross-test state
    private String teamPid;
    private String memberPid;

    // ========== Team CRUD Tests ==========

    @Test
    @Order(1)
    @DisplayName("T1-01: createTeam should persist team with correct fields")
    void T1_01_createTeam_persistsWithCorrectFields() {
        TeamCreateRequest request = new TeamCreateRequest();
        request.setCode("team-" + runId);
        request.setName("Integration Test Team " + runId);
        request.setDescription("Created by integration test");

        TeamResponse response = teamService.createTeam(
                request, getTestTenant().getId(), getTestUser().getId());

        assertThat(response).isNotNull();
        assertThat(response.getPid()).isNotBlank();
        assertThat(response.getCode()).isEqualTo("team-" + runId);
        assertThat(response.getName()).isEqualTo("Integration Test Team " + runId);

        this.teamPid = response.getPid();
        log.info("T1-01: created team pid={}", teamPid);
    }

    @Test
    @Order(2)
    @DisplayName("T1-02: getTeamByPid should return the correct team")
    void T1_02_getTeamByPid_returnsTeam() {
        assertThat(teamPid).as("teamPid must be set by T1-01").isNotBlank();

        TeamResponse response = teamService.getTeamByPid(teamPid);

        assertThat(response).isNotNull();
        assertThat(response.getPid()).isEqualTo(teamPid);
        assertThat(response.getCode()).isEqualTo("team-" + runId);
        assertThat(response.getName()).isEqualTo("Integration Test Team " + runId);
    }

    @Test
    @Order(3)
    @DisplayName("T1-03: listTeams should include the created team")
    void T1_03_listTeams_includesCreatedTeam() {
        assertThat(teamPid).as("teamPid must be set by T1-01").isNotBlank();

        List<TeamResponse> teams = teamService.listTeams(getTestTenant().getId());

        assertThat(teams).isNotNull();
        assertThat(teams).isNotEmpty();
        boolean found = teams.stream().anyMatch(t -> teamPid.equals(t.getPid()));
        assertThat(found).as("listTeams should include the team created in T1-01").isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("T1-04: updateTeam should change the team name")
    void T1_04_updateTeam_changesName() {
        assertThat(teamPid).as("teamPid must be set by T1-01").isNotBlank();

        TeamUpdateRequest request = new TeamUpdateRequest();
        request.setName("Updated Team Name " + runId);

        TeamResponse response = teamService.updateTeam(teamPid, request, getTestUser().getId());

        assertThat(response).isNotNull();
        assertThat(response.getName()).isEqualTo("Updated Team Name " + runId);
    }

    @Test
    @Order(5)
    @DisplayName("T1-05: createTeam with duplicate code should throw an exception")
    void T1_05_createTeam_duplicateCode_throwsException() {
        // Use the same code as the team created in T1-01
        TeamCreateRequest request = new TeamCreateRequest();
        request.setCode("team-" + runId); // same code
        request.setName("Duplicate Code Team");

        assertThatThrownBy(() ->
                teamService.createTeam(request, getTestTenant().getId(), getTestUser().getId()))
                .as("Creating a team with duplicate code should throw an exception")
                .isInstanceOf(Exception.class);
    }

    // ========== Member Management Tests ==========

    @Test
    @Order(6)
    @DisplayName("T1-06: addMember should add user to the team")
    void T1_06_addMember_addsUserToTeam() {
        assertThat(teamPid).as("teamPid must be set by T1-01").isNotBlank();

        TeamMemberAddRequest request = new TeamMemberAddRequest();
        request.setUserId(getTestUser().getId());
        request.setRole("member");

        TeamMemberResponse response = teamMemberService.addMember(
                teamPid, request, getTestUser().getId());

        assertThat(response).isNotNull();
        assertThat(response.getPid()).isNotBlank();
        assertThat(response.getUserId()).isEqualTo(getTestUser().getId());

        this.memberPid = response.getPid();
        log.info("T1-06: added member pid={}", memberPid);
    }

    @Test
    @Order(7)
    @DisplayName("T1-07: listMembers should return the added member")
    void T1_07_listMembers_returnsAddedMember() {
        assertThat(teamPid).as("teamPid must be set by T1-01").isNotBlank();
        assertThat(memberPid).as("memberPid must be set by T1-06").isNotBlank();

        List<TeamMemberResponse> members = teamMemberService.listMembers(teamPid);

        assertThat(members).isNotNull();
        assertThat(members).isNotEmpty();
        assertThat(members.stream().anyMatch(m -> getTestUser().getId().equals(m.getUserId()))).isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("T1-08: getCurrentUserTeams should return teams the user belongs to")
    void T1_08_getCurrentUserTeams_returnsTeamsUserBelongsTo() {
        assertThat(teamPid).as("teamPid must be set by T1-01").isNotBlank();

        List<TeamResponse> userTeams = teamService.getCurrentUserTeams(
                getTestUser().getId(), getTestTenant().getId());

        assertThat(userTeams).isNotNull();
        assertThat(userTeams).isNotEmpty();
        boolean found = userTeams.stream().anyMatch(t -> teamPid.equals(t.getPid()));
        assertThat(found).as("getCurrentUserTeams should include the team the user is a member of").isTrue();
    }

    @Test
    @Order(9)
    @DisplayName("T1-09: removeMember should remove the user from the team")
    void T1_09_removeMember_removesUserFromTeam() {
        assertThat(teamPid).as("teamPid must be set by T1-01").isNotBlank();
        assertThat(memberPid).as("memberPid must be set by T1-06").isNotBlank();

        teamMemberService.removeMember(teamPid, memberPid);

        List<TeamMemberResponse> members = teamMemberService.listMembers(teamPid);
        boolean stillPresent = members.stream().anyMatch(m -> memberPid.equals(m.getPid()));
        assertThat(stillPresent).as("Member should no longer be present after removal").isFalse();
    }

    @Test
    @Order(10)
    @DisplayName("T1-10: getTeamPidsByUserId should reflect membership changes after removal")
    void T1_10_getTeamPidsByUserId_reflectsMembershipChangesAfterRemoval() {
        assertThat(teamPid).as("teamPid must be set by T1-01").isNotBlank();

        List<String> pids = teamMemberService.getTeamPidsByUserId(
                getTestUser().getId(), getTestTenant().getId());

        // After removal in T1-09, user should no longer be in this team
        assertThat(pids).isNotNull();
        boolean stillInTeam = pids.contains(teamPid);
        assertThat(stillInTeam).as("getTeamPidsByUserId should not include teamPid after member removal").isFalse();
    }

    // ========== Delete & Code Reuse Tests ==========

    @Test
    @Order(11)
    @DisplayName("T1-11: deleteTeam should soft-delete the team and hide it from list")
    void T1_11_deleteTeam_softDeletesTeam_notVisibleInList() {
        assertThat(teamPid).as("teamPid must be set by T1-01").isNotBlank();

        teamService.deleteTeam(teamPid);

        List<TeamResponse> teams = teamService.listTeams(getTestTenant().getId());
        boolean stillVisible = teams.stream().anyMatch(t -> teamPid.equals(t.getPid()));
        assertThat(stillVisible).as("Deleted team should not appear in listTeams").isFalse();
    }

    @Test
    @Order(12)
    @DisplayName("T1-12: deleted team code should be reusable for a new team")
    void T1_12_deletedTeamCode_canBeReused() {
        // Deleted team's code should be reusable due to partial unique index WHERE NOT deleted
        TeamCreateRequest request = new TeamCreateRequest();
        request.setCode("team-" + runId); // same code as the now-deleted team
        request.setName("Reuse Code Team " + runId);

        TeamResponse response = teamService.createTeam(
                request, getTestTenant().getId(), getTestUser().getId());

        assertThat(response).isNotNull();
        assertThat(response.getPid()).isNotBlank();
        assertThat(response.getCode()).isEqualTo("team-" + runId);
        assertThat(response.getPid()).as("New team should have a different pid than the deleted one").isNotEqualTo(teamPid);
    }
}
