package com.auraboot.framework.integration.tenant;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.organization.dto.TeamCreateRequest;
import com.auraboot.framework.organization.dto.TeamMemberAddRequest;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.organization.service.TeamService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("TenantMemberController - Integration Tests")
class TenantMemberControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private TeamService teamService;

    @Autowired
    private TeamMemberService teamMemberService;

    private MockMvc mockMvc;

    private String teamAlphaPid;
    private String teamBravoPid;

    private final String runId = String.valueOf(System.nanoTime());

    @BeforeEach
    void setup() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // Ensure test user is a tenant member
        if (tenantMemberService.findByTenantIdAndUserId(tenantId, userId) == null) {
            tenantMemberService.addMember(userId, tenantId, "active");
        }

        // Set context so TeamMemberService.addMember can read tenantId
        MetaContext.setContext(tenantId, userId, getTestUser().getPid(), getTestUser().getUserName());

        // Create two distinct teams for this test run
        teamAlphaPid = ensureTeam("ctrl-alpha-" + runId, "Ctrl Alpha " + runId, tenantId, userId);
        teamBravoPid = ensureTeam("ctrl-bravo-" + runId, "Ctrl Bravo " + runId, tenantId, userId);

        // Add test user to both teams
        ensureUserInTeam(userId, teamAlphaPid);
        ensureUserInTeam(userId, teamBravoPid);

        MetaContext.clear();

        // Set up MockMvc with a filter that injects MetaContext per request
        Filter metaContextFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(
                        getTestTenant().getId(),
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        getTestUser().getUserName()
                );
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
            }
        };
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    @Test
    @DisplayName("GET /api/tenant/members/current/teams should return PIDs of teams the user belongs to")
    void getCurrentUserTeams() throws Exception {
        mockMvc.perform(get("/api/tenant/members/current/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@=='" + teamAlphaPid + "')]").exists())
                .andExpect(jsonPath("$.data[?(@=='" + teamBravoPid + "')]").exists());
    }

    // ===== helpers =====

    private String ensureTeam(String code, String name, Long tenantId, Long operatorId) {
        try {
            TeamCreateRequest req = new TeamCreateRequest();
            req.setCode(code);
            req.setName(name);
            return teamService.createTeam(req, tenantId, operatorId).getPid();
        } catch (Exception e) {
            return teamService.lambdaQuery()
                    .eq(com.auraboot.framework.organization.entity.Team::getTenantId, tenantId)
                    .eq(com.auraboot.framework.organization.entity.Team::getCode, code)
                    .one()
                    .getPid();
        }
    }

    private void ensureUserInTeam(Long userId, String teamPid) {
        try {
            TeamMemberAddRequest req = new TeamMemberAddRequest();
            req.setUserId(userId);
            req.setRole("member");
            teamMemberService.addMember(teamPid, req, userId);
        } catch (Exception e) {
            // Already a member — ignore
        }
    }
}
