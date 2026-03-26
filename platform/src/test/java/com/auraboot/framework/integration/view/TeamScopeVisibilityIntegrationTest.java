package com.auraboot.framework.integration.view;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.dashboard.dto.DashboardCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.dto.DashboardQueryRequest;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.organization.dto.TeamCreateRequest;
import com.auraboot.framework.organization.dto.TeamMemberAddRequest;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.organization.service.TeamService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.service.SavedViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TEAM Scope Visibility - Integration Tests")
class TeamScopeVisibilityIntegrationTest extends BaseIntegrationTest {

    private static final String MODEL_CODE = "device";
    private static final String PAGE_KEY = "team_scope_visibility";

    @Autowired
    private SavedViewService savedViewService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private TeamService teamService;

    @Autowired
    private TeamMemberService teamMemberService;

    private User creatorUser;
    private User sameTeamUser;
    private User otherTeamUser;

    // Team PIDs resolved at setup time (random, not constant strings)
    private String teamAlphaPid;
    private String teamBravoPid;

    // Use a per-instance run suffix to avoid cross-run conflicts
    private final String runId = String.valueOf(System.nanoTime());

    @BeforeEach
    void setupUsers() {
        creatorUser = getTestUser();
        Long tenantId = getTestTenant().getId();
        Long creatorId = creatorUser.getId();

        // Ensure creator is a tenant member
        if (tenantMemberService.findByTenantIdAndUserId(tenantId, creatorId) == null) {
            tenantMemberService.addMember(creatorId, tenantId, "active");
        }

        // Create unique teams for this test run
        teamAlphaPid = ensureTeam("alpha-" + runId, "Team Alpha " + runId, tenantId, creatorId);
        teamBravoPid = ensureTeam("bravo-" + runId, "Team Bravo " + runId, tenantId, creatorId);

        // Add creatorUser to teamAlpha
        setContext(creatorUser);
        ensureUserInTeam(creatorUser, teamAlphaPid);

        // Create sameTeamUser in teamAlpha
        sameTeamUser = ensureUserWithTeam(teamAlphaPid);

        // Create otherTeamUser in teamBravo
        otherTeamUser = ensureUserWithTeam(teamBravoPid);

        setContext(creatorUser);
    }

    @Test
    @DisplayName("SavedView TEAM visibility should allow same team and deny other team")
    void savedViewTeamVisibilityMatrix() {
        setContext(creatorUser);
        SavedViewCreateRequest createRequest = new SavedViewCreateRequest();
        createRequest.setName("Team View " + System.nanoTime());
        createRequest.setModelCode(MODEL_CODE);
        createRequest.setPageKey(PAGE_KEY);
        createRequest.setScope("team");
        createRequest.setTeamId(teamAlphaPid);
        SavedViewDTO created = savedViewService.create(createRequest);

        setContext(sameTeamUser);
        List<SavedViewDTO> sameTeamViews = savedViewService.getAccessibleViews(MODEL_CODE, PAGE_KEY);
        assertTrue(sameTeamViews.stream().anyMatch(v -> v.getPid().equals(created.getPid())));
        assertDoesNotThrow(() -> savedViewService.findByPid(created.getPid()));

        setContext(otherTeamUser);
        List<SavedViewDTO> otherTeamViews = savedViewService.getAccessibleViews(MODEL_CODE, PAGE_KEY);
        assertFalse(otherTeamViews.stream().anyMatch(v -> v.getPid().equals(created.getPid())));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> savedViewService.findByPid(created.getPid()));
        assertEquals(ResponseCode.FORBIDDEN, ex.getResponseCode());
    }

    @Test
    @DisplayName("SavedView TEAM create should reject non-team-member")
    void savedViewTeamCreateRejectsNonMember() {
        setContext(otherTeamUser);
        SavedViewCreateRequest createRequest = new SavedViewCreateRequest();
        createRequest.setName("Illegal Team View " + System.nanoTime());
        createRequest.setModelCode(MODEL_CODE);
        createRequest.setPageKey(PAGE_KEY);
        createRequest.setScope("team");
        createRequest.setTeamId(teamAlphaPid);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> savedViewService.create(createRequest));
        assertEquals(ResponseCode.FORBIDDEN, ex.getResponseCode());
    }

    @Test
    @DisplayName("Dashboard TEAM visibility should allow same team and deny other team")
    void dashboardTeamVisibilityMatrix() {
        setContext(creatorUser);
        DashboardCreateRequest createRequest = new DashboardCreateRequest();
        createRequest.setTitle("Team Dashboard " + System.nanoTime());
        createRequest.setScope("team");
        createRequest.setTeamId(teamAlphaPid);
        DashboardDTO created = dashboardService.create(createRequest);

        setContext(sameTeamUser);
        DashboardQueryRequest queryRequest = new DashboardQueryRequest();
        List<DashboardDTO> sameTeamDashboards = dashboardService.getAccessibleDashboards(queryRequest);
        assertTrue(sameTeamDashboards.stream().anyMatch(d -> d.getPid().equals(created.getPid())));
        assertDoesNotThrow(() -> dashboardService.findByPid(created.getPid()));

        setContext(otherTeamUser);
        List<DashboardDTO> otherTeamDashboards = dashboardService.getAccessibleDashboards(queryRequest);
        assertFalse(otherTeamDashboards.stream().anyMatch(d -> d.getPid().equals(created.getPid())));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> dashboardService.findByPid(created.getPid()));
        assertEquals(ResponseCode.FORBIDDEN, ex.getResponseCode());
    }

    @Test
    @DisplayName("Dashboard TEAM create should reject non-team-member")
    void dashboardTeamCreateRejectsNonMember() {
        setContext(otherTeamUser);
        DashboardCreateRequest createRequest = new DashboardCreateRequest();
        createRequest.setTitle("Illegal Team Dashboard " + System.nanoTime());
        createRequest.setScope("team");
        createRequest.setTeamId(teamAlphaPid);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> dashboardService.create(createRequest));
        assertEquals(ResponseCode.FORBIDDEN, ex.getResponseCode());
    }

    // ===== helpers =====

    /**
     * Create or find a team by code. Returns the team's PID.
     */
    private String ensureTeam(String code, String name, Long tenantId, Long operatorId) {
        try {
            TeamCreateRequest req = new TeamCreateRequest();
            req.setCode(code);
            req.setName(name);
            return teamService.createTeam(req, tenantId, operatorId).getPid();
        } catch (Exception e) {
            // Team with this code already exists — look it up
            return teamService.lambdaQuery()
                    .eq(com.auraboot.framework.organization.entity.Team::getTenantId, tenantId)
                    .eq(com.auraboot.framework.organization.entity.Team::getCode, code)
                    .one()
                    .getPid();
        }
    }

    /**
     * Create a new user and add them to the given team.
     */
    private User ensureUserWithTeam(String teamPid) {
        // Keep email <= 64 chars: "ts-" (3) + last8 of pid (8) + "-" + 10 digit suffix (10) + "@a.test" (7) = 38 chars
        String suffix = teamPid.substring(Math.max(0, teamPid.length() - 8)) + "-" + (System.nanoTime() % 10_000_000_000L);
        String email = "ts-" + suffix + "@a.test";
        User user = userService.signUp(email, "test-password-123");
        Long tenantId = getTestTenant().getId();
        if (tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId()) == null) {
            tenantMemberService.addMember(user.getId(), tenantId, "active");
        }
        ensureUserInTeam(user, teamPid);
        return user;
    }

    /**
     * Add user to team via ab_team_member (using actual platform service).
     */
    private void ensureUserInTeam(User user, String teamPid) {
        try {
            TeamMemberAddRequest req = new TeamMemberAddRequest();
            req.setUserId(user.getId());
            req.setRole("member");
            teamMemberService.addMember(teamPid, req, creatorUser != null ? creatorUser.getId() : user.getId());
        } catch (Exception e) {
            // Already a member — ignore
        }
    }

    private void setContext(User user) {
        MetaContext.setContext(getTestTenant().getId(), user.getId(), user.getPid(), user.getUserName());
    }
}
