package com.auraboot.framework.organization.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.organization.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for TeamService and TeamMemberService.
 *
 * <p>Covers scenarios not exercised by OrganizationTeamIntegrationTest:
 * <ul>
 *   <li>TS-01: createTeam persists all provided fields (description, status)</li>
 *   <li>TS-02: getTeamByPid throws when team does not exist</li>
 *   <li>TS-03: updateTeam can change status to INACTIVE</li>
 *   <li>TS-04: updateTeam on non-existent pid throws</li>
 *   <li>TS-05: addMember with duplicate user throws BusinessException</li>
 *   <li>TS-06: addMember with non-existent userId throws BusinessException</li>
 *   <li>TS-07: getTeamMembershipsByUserId returns role and teamCode details</li>
 *   <li>TS-08: removeMember on non-existent memberPid throws BusinessException</li>
 *   <li>TS-09: listMembers on non-existent teamPid throws BusinessException</li>
 * </ul>
 */
@Slf4j
@DisplayName("TeamService Integration Tests (complementary)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeamServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TeamService teamService;

    @Autowired
    private TeamMemberService teamMemberService;

    /** Unique per run to avoid collisions with other test suites. */
    private final String runId = "ts-" + System.currentTimeMillis();

    // Shared state across ordered tests
    private String teamPid;
    private String memberPid;

    // ==================== TS-01: createTeam persists all fields ====================

    @Test
    @Order(1)
    @DisplayName("TS-01: createTeam persists code, name, description and sets status to ACTIVE")
    void TS_01_createTeam_persistsAllFields() {
        TeamCreateRequest request = new TeamCreateRequest();
        request.setCode("ts-" + runId);
        request.setName("TS Team " + runId);
        request.setDescription("Description for " + runId);

        TeamResponse response = teamService.createTeam(
                request, getTestTenant().getId(), getTestUser().getId());

        assertThat(response).isNotNull();
        assertThat(response.getPid()).isNotBlank();
        assertThat(response.getCode()).isEqualTo("ts-" + runId);
        assertThat(response.getName()).isEqualTo("TS Team " + runId);
        assertThat(response.getDescription()).isEqualTo("Description for " + runId);
        // Service always initialises new teams as ACTIVE
        assertThat(response.getStatus()).isEqualTo("active");
        assertThat(response.getMemberCount()).isEqualTo(0);

        this.teamPid = response.getPid();
        log.info("TS-01: team created pid={}", teamPid);
    }

    // ==================== TS-02: getTeamByPid throws on unknown pid ====================

    @Test
    @Order(2)
    @DisplayName("TS-02: getTeamByPid throws BusinessException for non-existent pid")
    void TS_02_getTeamByPid_nonExistentPid_throws() {
        assertThatThrownBy(() -> teamService.getTeamByPid("non-existent-pid-" + runId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Team not found");
    }

    // ==================== TS-03: updateTeam status to INACTIVE ====================

    @Test
    @Order(3)
    @DisplayName("TS-03: updateTeam can deactivate a team by setting status to INACTIVE")
    void TS_03_updateTeam_changesStatusToInactive() {
        assertThat(teamPid).as("teamPid must be set by TS-01").isNotBlank();

        TeamUpdateRequest request = new TeamUpdateRequest();
        request.setStatus("inactive");

        TeamResponse response = teamService.updateTeam(teamPid, request, getTestUser().getId());

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("inactive");

        // Restore ACTIVE for subsequent tests
        TeamUpdateRequest restoreRequest = new TeamUpdateRequest();
        restoreRequest.setStatus("active");
        teamService.updateTeam(teamPid, restoreRequest, getTestUser().getId());
    }

    // ==================== TS-04: updateTeam on unknown pid throws ====================

    @Test
    @Order(4)
    @DisplayName("TS-04: updateTeam throws BusinessException for non-existent pid")
    void TS_04_updateTeam_nonExistentPid_throws() {
        TeamUpdateRequest request = new TeamUpdateRequest();
        request.setName("Should fail");

        assertThatThrownBy(() -> teamService.updateTeam("ghost-pid-" + runId, request, getTestUser().getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Team not found");
    }

    // ==================== TS-05: addMember duplicate guard ====================

    @Test
    @Order(5)
    @DisplayName("TS-05: addMember throws BusinessException when user is already a team member")
    void TS_05_addMember_duplicateUser_throws() {
        assertThat(teamPid).as("teamPid must be set by TS-01").isNotBlank();

        TeamMemberAddRequest request = new TeamMemberAddRequest();
        request.setUserId(getTestUser().getId());
        request.setRole("member");

        // First add should succeed
        TeamMemberResponse first = teamMemberService.addMember(teamPid, request, getTestUser().getId());
        assertThat(first.getPid()).isNotBlank();
        this.memberPid = first.getPid();

        // Second add of the same user must fail
        assertThatThrownBy(() ->
                teamMemberService.addMember(teamPid, request, getTestUser().getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already a member");

        log.info("TS-05: duplicate member guard verified for user={}", getTestUser().getId());
    }

    // ==================== TS-06: addMember with non-existent userId throws ====================

    @Test
    @Order(6)
    @DisplayName("TS-06: addMember throws BusinessException for non-existent userId")
    void TS_06_addMember_nonExistentUser_throws() {
        assertThat(teamPid).as("teamPid must be set by TS-01").isNotBlank();

        TeamMemberAddRequest request = new TeamMemberAddRequest();
        request.setUserId(-999_999_999L); // guaranteed not to exist
        request.setRole("member");

        assertThatThrownBy(() ->
                teamMemberService.addMember(teamPid, request, getTestUser().getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User not found");
    }

    // ==================== TS-07: getTeamMembershipsByUserId ====================

    @Test
    @Order(7)
    @DisplayName("TS-07: getTeamMembershipsByUserId returns role and teamCode for each membership")
    void TS_07_getTeamMembershipsByUserId_returnsMembershipDetails() {
        assertThat(teamPid).as("teamPid must be set by TS-01").isNotBlank();
        assertThat(memberPid).as("memberPid must be set by TS-05").isNotBlank();

        List<Map<String, Object>> memberships = teamMemberService.getTeamMembershipsByUserId(
                getTestUser().getId(), getTestTenant().getId());

        assertThat(memberships).isNotNull().isNotEmpty();

        // Find the membership for the team we created
        Map<String, Object> found = memberships.stream()
                .filter(m -> teamPid.equals(m.get("teamPid")))
                .findFirst()
                .orElse(null);

        assertThat(found).as("Membership for teamPid=%s should be present".formatted(teamPid)).isNotNull();
        assertThat(found.get("teamCode")).isEqualTo("ts-" + runId);
        assertThat(found.get("role")).isNotNull();
        assertThat(found.get("joinedAt")).isNotNull();
    }

    // ==================== TS-08: removeMember on non-existent memberPid throws ====================

    @Test
    @Order(8)
    @DisplayName("TS-08: removeMember throws BusinessException for non-existent memberPid")
    void TS_08_removeMember_nonExistentMemberPid_throws() {
        assertThat(teamPid).as("teamPid must be set by TS-01").isNotBlank();

        assertThatThrownBy(() ->
                teamMemberService.removeMember(teamPid, "ghost-member-" + runId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Team member not found");
    }

    // ==================== TS-09: listMembers on non-existent teamPid throws ====================

    @Test
    @Order(9)
    @DisplayName("TS-09: listMembers throws BusinessException for non-existent teamPid")
    void TS_09_listMembers_nonExistentTeamPid_throws() {
        assertThatThrownBy(() ->
                teamMemberService.listMembers("ghost-team-" + runId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Team not found");
    }
}
