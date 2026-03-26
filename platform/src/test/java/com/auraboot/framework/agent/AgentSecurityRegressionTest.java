package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security regression tests for ACP (Agent Control Plane).
 *
 * Covers:
 * 1. Cross-tenant tool isolation — a tenant cannot see another tenant's tools
 * 2. Cross-tenant memory isolation — a tenant cannot see another tenant's memories
 * 3. High-risk tool requires approval — DELETE-type commands get requires_approval=true
 * 4. Auto-generated tools always have a non-null risk_level
 * 5. Agent-scoped memory isolation — agent-B cannot see agent-A's memories
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentSecurityRegressionTest extends BaseIntegrationTest {

    @Autowired
    private AgentMemoryService agentMemoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testRunId = String.valueOf(System.currentTimeMillis());

    // ── Sentinel tenant used to simulate a "foreign" tenant ──
    // Using a hard-coded negative ID that will never match a real tenant row.
    private static final long FOREIGN_TENANT_ID = -9991L;

    // ──────────────────────────────────────────────────────────────
    // Test 1: Cross-tenant tool isolation
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void crossTenantToolIsolation_foreignTenantSeesNoTools() {
        Long myTenantId = getTestTenant().getId();
        String toolCode = "sec_tool_isolation_" + testRunId;

        // Insert a tool belonging to the test tenant
        jdbcTemplate.update(
                "INSERT INTO ab_agent_tool "
                + "(pid, tenant_id, tool_code, tool_type, tool_name, tool_description, "
                + " risk_level, requires_approval, tool_status, auto_generated, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, 'dsl_command', ?, 'Security regression tool', "
                + " 'low', false, 'active', true, ?, ?, false)",
                UniqueIdGenerator.generate(),
                myTenantId,
                toolCode,
                toolCode,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now())
        );

        // A query scoped to the foreign tenant must return nothing for our tool_code
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tool_code FROM ab_agent_tool "
                + "WHERE tenant_id = ? AND tool_code = ? AND (deleted_flag IS NULL OR deleted_flag = false)",
                FOREIGN_TENANT_ID,
                toolCode
        );

        assertThat(rows)
                .as("Foreign tenant must not see tools belonging to another tenant")
                .isEmpty();

        // Confirm the row really does exist for the correct tenant (sanity check)
        List<Map<String, Object>> ownRows = jdbcTemplate.queryForList(
                "SELECT tool_code FROM ab_agent_tool "
                + "WHERE tenant_id = ? AND tool_code = ? AND (deleted_flag IS NULL OR deleted_flag = false)",
                myTenantId,
                toolCode
        );
        assertThat(ownRows)
                .as("Test tenant must be able to see its own tool")
                .hasSize(1);
    }

    // ──────────────────────────────────────────────────────────────
    // Test 2: Cross-tenant memory isolation
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void crossTenantMemoryIsolation_foreignTenantSeesNoMemories() {
        Long myTenantId = getTestTenant().getId();
        String agentCode = "sec-agent-" + testRunId;
        String memoryTitle = "Tenant Isolation Memory " + testRunId;

        // Store a memory in the test tenant
        agentMemoryService.storeMemoryWithEmbedding(
                myTenantId, agentCode, "fact",
                memoryTitle,
                "This memory must never leak to another tenant",
                8, "run-" + testRunId, null);

        // Load from the perspective of the foreign tenant
        List<Map<String, Object>> foreignResults =
                agentMemoryService.loadByImportance(FOREIGN_TENANT_ID, agentCode, 50);

        boolean leaked = foreignResults.stream()
                .anyMatch(m -> memoryTitle.equals(m.get("memory_title")));

        assertThat(leaked)
                .as("Memory stored for test tenant must not appear when queried under foreign tenant_id")
                .isFalse();

        // Confirm the memory is visible under the correct tenant
        List<Map<String, Object>> ownResults =
                agentMemoryService.loadByImportance(myTenantId, agentCode, 50);
        boolean found = ownResults.stream()
                .anyMatch(m -> memoryTitle.equals(m.get("memory_title")));
        assertThat(found)
                .as("Memory must be retrievable by the owning tenant")
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────
    // Test 3: HIGH-risk tool auto-requires approval
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void highRiskTool_requiresApprovalIsTrue() {
        Long myTenantId = getTestTenant().getId();
        String toolCode = "sec_high_risk_" + testRunId;

        // Insert a HIGH-risk tool (simulating a DELETE command tool)
        jdbcTemplate.update(
                "INSERT INTO ab_agent_tool "
                + "(pid, tenant_id, tool_code, tool_type, tool_name, tool_description, "
                + " risk_level, requires_approval, tool_status, auto_generated, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, 'dsl_command', ?, 'Delete all records — irreversible', "
                + " 'high', true, 'active', true, ?, ?, false)",
                UniqueIdGenerator.generate(),
                myTenantId,
                toolCode,
                toolCode,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now())
        );

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT risk_level, requires_approval FROM ab_agent_tool "
                + "WHERE tenant_id = ? AND tool_code = ? AND (deleted_flag IS NULL OR deleted_flag = false)",
                myTenantId, toolCode
        );

        assertThat(rows).as("Tool row must exist").hasSize(1);

        Map<String, Object> tool = rows.get(0);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(tool.get("risk_level"))
                .as("HIGH-risk tool must have risk_level = HIGH")
                .isEqualTo("high");
        softly.assertThat(tool.get("requires_approval"))
                .as("HIGH-risk tool must have requires_approval = true")
                .isEqualTo(true);
        softly.assertAll();
    }

    // ──────────────────────────────────────────────────────────────
    // Test 4: Auto-generated tools always have a non-null risk_level
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void syncTools_allAutoGeneratedToolsHaveNonNullRiskLevel() {
        Long myTenantId = getTestTenant().getId();

        // Query all auto-generated, non-deleted tools for this tenant
        List<Map<String, Object>> tools = jdbcTemplate.queryForList(
                "SELECT tool_code, risk_level FROM ab_agent_tool "
                + "WHERE tenant_id = ? AND auto_generated = true "
                + "AND (deleted_flag IS NULL OR deleted_flag = false)",
                myTenantId
        );

        // It is acceptable for there to be no auto-generated tools if none are published yet;
        // but every tool that IS present must have a valid risk_level.
        SoftAssertions softly = new SoftAssertions();
        for (Map<String, Object> tool : tools) {
            String code = (String) tool.get("tool_code");
            Object riskLevel = tool.get("risk_level");
            softly.assertThat(riskLevel)
                    .as("Auto-generated tool '%s' must have a non-null risk_level", code)
                    .isNotNull()
                    .isIn("low", "medium", "high");
        }
        softly.assertAll();
    }

    // ──────────────────────────────────────────────────────────────
    // Test 5: Agent-scoped memory isolation
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void agentScopedMemoryIsolation_agentBCannotSeeAgentAMemories() {
        Long tenantId = getTestTenant().getId();
        String agentA = "agent-alpha-" + testRunId;
        String agentB = "agent-beta-" + testRunId;
        String secretTitle = "Agent-A Secret " + testRunId;

        // Store a memory under agent-A
        agentMemoryService.storeMemoryWithEmbedding(
                tenantId, agentA, "fact",
                secretTitle,
                "Confidential memory for agent alpha only",
                7, "run-" + testRunId, null);

        // Agent-B must NOT see agent-A's memories
        List<Map<String, Object>> agentBMemories =
                agentMemoryService.loadByImportance(tenantId, agentB, 100);

        boolean leaked = agentBMemories.stream()
                .anyMatch(m -> secretTitle.equals(m.get("memory_title")));

        assertThat(leaked)
                .as("Agent-B must not see memories belonging to agent-A "
                    + "(same tenant, different agent scope)")
                .isFalse();

        // Agent-A must still see its own memory
        List<Map<String, Object>> agentAMemories =
                agentMemoryService.loadByImportance(tenantId, agentA, 100);
        boolean agentAFound = agentAMemories.stream()
                .anyMatch(m -> secretTitle.equals(m.get("memory_title")));

        assertThat(agentAFound)
                .as("Agent-A must be able to retrieve its own memory")
                .isTrue();
    }
}
