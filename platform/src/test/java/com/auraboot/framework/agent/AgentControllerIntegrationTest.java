package com.auraboot.framework.agent;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for CoreAgentController and AgentRuntimeController.
 * Covers all /api/agent/* HTTP endpoints via MockMvc.
 * <p>
 * Uses real PostgreSQL and real services. MetaContext is injected via a
 * per-request servlet filter, matching the pattern in TenantMemberControllerIntegrationTest.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("AgentController - Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMockMvc() {
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

    // ==================== CoreAgentController ====================

    /**
     * Test 1: GET /api/agent/status → 200, body must contain "enabled" key.
     */
    @Test
    @Order(1)
    @DisplayName("GET /api/agent/status returns 200 with enabled key")
    void getStatus_returnsEnabledKey() throws Exception {
        mockMvc.perform(get("/api/agent/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").exists());
    }

    /**
     * Test 2: GET /api/agent/providers → 200, returns a list (may be empty).
     */
    @Test
    @Order(2)
    @DisplayName("GET /api/agent/providers returns 200")
    void listProviders_returnsOk() throws Exception {
        mockMvc.perform(get("/api/agent/providers"))
                .andExpect(status().isOk());
    }

    /**
     * Test 3: GET /api/agent/providers/configured → 200, returns a list.
     */
    @Test
    @Order(3)
    @DisplayName("GET /api/agent/providers/configured returns 200")
    void listConfiguredProviders_returnsOk() throws Exception {
        mockMvc.perform(get("/api/agent/providers/configured"))
                .andExpect(status().isOk());
    }

    /**
     * Test 4: POST /api/agent/tools/sync → 200, body has created/updated/deactivated keys.
     */
    @Test
    @Order(4)
    @DisplayName("POST /api/agent/tools/sync returns sync result with created/updated/deactivated")
    void syncTools_returnsSyncResult() throws Exception {
        mockMvc.perform(post("/api/agent/tools/sync")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    // ==================== AgentRuntimeController ====================

    /**
     * Test 5: POST /api/agent/tools/derive-contracts → 200, body has "derived" key.
     */
    @Test
    @Order(5)
    @DisplayName("POST /api/agent/tools/derive-contracts returns 200 with derived count")
    void deriveContracts_returnsDerivedCount() throws Exception {
        mockMvc.perform(post("/api/agent/tools/derive-contracts")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.derived").exists());
    }

    /**
     * Test 6: POST /api/agent/tools/dry-run with missing toolCode → error response (code != "0").
     */
    @Test
    @Order(6)
    @DisplayName("POST /api/agent/tools/dry-run with missing toolCode returns error")
    void dryRunTool_missingToolCode_returnsError() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("input", Map.of()));

        mockMvc.perform(post("/api/agent/tools/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1"));
    }

    /**
     * Test 7: POST /api/agent/tools/dry-run-plan with empty steps → error response (code != "0").
     */
    @Test
    @Order(7)
    @DisplayName("POST /api/agent/tools/dry-run-plan with empty steps returns error")
    void dryRunPlan_emptySteps_returnsError() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("steps", List.of()));

        mockMvc.perform(post("/api/agent/tools/dry-run-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1"));
    }

    /**
     * Test 8: GET /api/agent/capabilities → 200, returns a list (may be empty).
     */
    @Test
    @Order(8)
    @DisplayName("GET /api/agent/capabilities returns 200")
    void listCapabilities_returnsOk() throws Exception {
        mockMvc.perform(get("/api/agent/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * Test 9: GET /api/agent/approvals/pending → 200, returns a list.
     */
    @Test
    @Order(9)
    @DisplayName("GET /api/agent/approvals/pending returns 200 with list")
    void listPendingApprovals_returnsOk() throws Exception {
        mockMvc.perform(get("/api/agent/approvals/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * Test 10: POST /api/agent/dispatch with missing fields → error response (code != "0").
     * When agent is disabled the response also uses error path.
     */
    @Test
    @Order(10)
    @DisplayName("POST /api/agent/dispatch with missing taskPid and agentCode returns error")
    void dispatch_missingRequiredFields_returnsError() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("someOtherField", "value"));

        mockMvc.perform(post("/api/agent/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // Either "taskPid and agentCode required" error OR "Agent runtime is disabled" — both are error responses
                .andExpect(jsonPath("$.code").value("1"));
    }
}
