package com.auraboot.framework.view;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.entity.TeamMember;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.organization.mapper.TeamMemberMapper;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.service.SavedViewService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for SavedView TEAM scope features.
 * Covers team view creation, default management, duplication with scope preservation,
 * and teamName population in DTO.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("SavedView TEAM Scope Integration Tests (SVT-01~SVT-08)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SavedViewTeamScopeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SavedViewService savedViewService;

    @Autowired
    private TeamMapper teamMapper;

    @Autowired
    private TeamMemberMapper teamMemberMapper;

    private final String runId = "svt-" + System.currentTimeMillis();
    private final String testModelCode = "test_svt_model_" + System.currentTimeMillis();

    // Cross-test state
    private String teamPid;
    private String teamViewPid;
    private String teamDefaultView1Pid;
    private String teamDefaultView2Pid;

    private boolean teamDataInitialized = false;

    /**
     * Create a test team and add current user as a member.
     * Called once on first test (cannot use @BeforeAll because testTenant
     * is initialized in BaseIntegrationTest @BeforeEach).
     */
    @BeforeEach
    void setupTeamData() {
        if (teamDataInitialized) return;
        teamDataInitialized = true;
        // Create a test team
        Team team = new Team();
        team.setPid(UniqueIdGenerator.generate());
        team.setTenantId(testTenant.getId());
        team.setCode(runId + "_team");
        team.setName(runId + " Test Team");
        team.setDescription("Test team for SavedView TEAM scope tests");
        team.setStatus("active");
        team.setDeletedFlag(false);
        team.setCreatedAt(Instant.now());
        team.setUpdatedAt(Instant.now());
        team.setCreatedBy(testUser.getId());
        team.setUpdatedBy(testUser.getId());
        teamMapper.insert(team);

        teamPid = team.getPid();
        log.info("Created test team: pid={}, name={}", teamPid, team.getName());

        // Add current user as team member
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

        log.info("Added user {} as team member of team {}", testUser.getId(), teamPid);
    }

    // ==================== SVT-01 ====================

    @Test
    @Order(1)
    @DisplayName("SVT-01: create TEAM view with teamId persists correctly")
    void svt01_createTeamView() {
        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName(runId + "-team-view");
        request.setModelCode(testModelCode);
        request.setScope("team");
        request.setTeamId(teamPid);
        request.setViewType("table");

        SavedViewDTO result = savedViewService.create(request);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isNotBlank();
        assertThat(result.getScope()).isEqualTo("team");
        assertThat(result.getTeamId()).isEqualTo(teamPid);
        assertThat(result.getModelCode()).isEqualTo(testModelCode);

        teamViewPid = result.getPid();
        log.info("SVT-01: created TEAM view pid={}", teamViewPid);
    }

    // ==================== SVT-02 ====================

    @Test
    @Order(2)
    @DisplayName("SVT-02: teamName is populated in DTO for TEAM scope views")
    void svt02_teamNamePopulated() {
        assertThat(teamViewPid).as("teamViewPid must be set by SVT-01").isNotBlank();

        SavedViewDTO result = savedViewService.findByPid(teamViewPid);

        assertThat(result).isNotNull();
        assertThat(result.getTeamName()).isNotNull();
        assertThat(result.getTeamName()).contains(runId);

        log.info("SVT-02: teamName populated as '{}'", result.getTeamName());
    }

    // ==================== SVT-03 ====================

    @Test
    @Order(3)
    @DisplayName("SVT-03: TEAM view visible in getAccessibleViews")
    void svt03_teamViewInAccessibleViews() {
        assertThat(teamViewPid).as("teamViewPid must be set by SVT-01").isNotBlank();

        List<SavedViewDTO> views = savedViewService.getAccessibleViews(testModelCode, null);

        assertThat(views).extracting(SavedViewDTO::getPid).contains(teamViewPid);

        // Also verify teamName is populated in list results
        SavedViewDTO teamView = views.stream()
                .filter(v -> teamViewPid.equals(v.getPid()))
                .findFirst()
                .orElse(null);
        assertThat(teamView).isNotNull();
        assertThat(teamView.getTeamName()).isNotNull();

        log.info("SVT-03: TEAM view found in accessible views with teamName={}", teamView.getTeamName());
    }

    // ==================== SVT-04 ====================

    @Test
    @Order(4)
    @DisplayName("SVT-04: setAsDefault for TEAM view clears previous TEAM default")
    void svt04_setTeamDefaultClearsPrevious() {
        // Create first TEAM view and set as default
        SavedViewCreateRequest req1 = new SavedViewCreateRequest();
        req1.setName(runId + "-team-default-1");
        req1.setModelCode(testModelCode);
        req1.setScope("team");
        req1.setTeamId(teamPid);
        req1.setViewType("table");
        SavedViewDTO view1 = savedViewService.create(req1);
        teamDefaultView1Pid = view1.getPid();

        savedViewService.setAsDefault(teamDefaultView1Pid);
        SavedViewDTO afterSet1 = savedViewService.findByPid(teamDefaultView1Pid);
        assertThat(afterSet1.getIsDefault()).isTrue();

        // Create second TEAM view and set as default
        SavedViewCreateRequest req2 = new SavedViewCreateRequest();
        req2.setName(runId + "-team-default-2");
        req2.setModelCode(testModelCode);
        req2.setScope("team");
        req2.setTeamId(teamPid);
        req2.setViewType("table");
        SavedViewDTO view2 = savedViewService.create(req2);
        teamDefaultView2Pid = view2.getPid();

        savedViewService.setAsDefault(teamDefaultView2Pid);

        // Verify: view1 should no longer be default, view2 should be default
        SavedViewDTO view1After = savedViewService.findByPid(teamDefaultView1Pid);
        SavedViewDTO view2After = savedViewService.findByPid(teamDefaultView2Pid);

        assertThat(view1After.getIsDefault()).isFalse();
        assertThat(view2After.getIsDefault()).isTrue();

        log.info("SVT-04: TEAM default cleared for view1={}, set for view2={}", teamDefaultView1Pid, teamDefaultView2Pid);
    }

    // ==================== SVT-05 ====================

    @Test
    @Order(5)
    @DisplayName("SVT-05: TEAM default does not affect PERSONAL default")
    void svt05_teamDefaultDoesNotAffectPersonal() {
        // Create a PERSONAL view and set as default
        SavedViewCreateRequest personalReq = new SavedViewCreateRequest();
        personalReq.setName(runId + "-personal-for-isolation");
        personalReq.setModelCode(testModelCode);
        personalReq.setScope("personal");
        personalReq.setViewType("table");
        SavedViewDTO personalView = savedViewService.create(personalReq);
        savedViewService.setAsDefault(personalView.getPid());

        // Now set a TEAM view as default
        assertThat(teamDefaultView1Pid).as("teamDefaultView1Pid must be set by SVT-04").isNotBlank();
        savedViewService.setAsDefault(teamDefaultView1Pid);

        // Verify PERSONAL default is unchanged
        SavedViewDTO personalAfter = savedViewService.findByPid(personalView.getPid());
        assertThat(personalAfter.getIsDefault()).isTrue();

        log.info("SVT-05: PERSONAL default unaffected by TEAM default change");
    }

    // ==================== SVT-06 ====================

    @Test
    @Order(6)
    @DisplayName("SVT-06: duplicate TEAM view preserves scope and teamId")
    void svt06_duplicatePreservesTeamScope() {
        assertThat(teamViewPid).as("teamViewPid must be set by SVT-01").isNotBlank();

        String dupName = runId + "-team-duplicate";
        SavedViewDTO duplicated = savedViewService.duplicate(teamViewPid, dupName);

        assertThat(duplicated).isNotNull();
        assertThat(duplicated.getPid()).isNotEqualTo(teamViewPid);
        assertThat(duplicated.getScope()).isEqualTo("team");
        assertThat(duplicated.getTeamId()).isEqualTo(teamPid);
        assertThat(duplicated.getName()).isEqualTo(dupName);
        assertThat(duplicated.getIsDefault()).isFalse();

        log.info("SVT-06: duplicated TEAM view pid={}, scope={}, teamId={}",
                duplicated.getPid(), duplicated.getScope(), duplicated.getTeamId());
    }

    // ==================== SVT-07 ====================

    @Test
    @Order(7)
    @DisplayName("SVT-07: TEAM view with non-member team is rejected")
    void svt07_nonMemberTeamRejected() {
        // Create a team where user is NOT a member
        String fakePid = "non-existent-team-" + System.currentTimeMillis();

        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName(runId + "-unauthorized-team-view");
        request.setModelCode(testModelCode);
        request.setScope("team");
        request.setTeamId(fakePid);
        request.setViewType("table");

        assertThatThrownBy(() -> savedViewService.create(request))
                .hasMessageContaining("not a member");

        log.info("SVT-07: non-member team access correctly rejected");
    }

    // ==================== SVT-08 ====================

    @Test
    @Order(8)
    @DisplayName("SVT-08: PERSONAL view teamName is null")
    void svt08_personalViewTeamNameNull() {
        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setName(runId + "-personal-no-team");
        request.setModelCode(testModelCode);
        request.setScope("personal");
        request.setViewType("table");

        SavedViewDTO result = savedViewService.create(request);

        assertThat(result.getTeamName()).isNull();
        assertThat(result.getTeamId()).isNull();

        log.info("SVT-08: PERSONAL view correctly has null teamName");
    }
}
