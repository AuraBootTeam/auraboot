package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.service.AgentScheduleService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the approval-gate check added to AgentScheduleService.
 *
 * Covers:
 *  1. agentHasApprovalRequiredTools() returns false when no tools require approval
 *  2. agentHasApprovalRequiredTools() returns true when at least one tool requires approval
 *
 * Uses real database (PostgreSQL), no mocks.
 * Data is NOT rolled back so it can be inspected manually.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentScheduleApprovalGateTest extends BaseIntegrationTest {

    @Autowired
    private AgentScheduleService scheduleService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private final String suffix = String.valueOf(System.currentTimeMillis());

    // Agent with no approval-required tools
    private final String safeAgentCode = "test-safe-agent-" + suffix;
    // Agent with one approval-required tool
    private final String riskyAgentCode = "test-risky-agent-" + suffix;

    // PIDs created during setup — kept for reference
    private String safeAgentPid;
    private String riskyAgentPid;
    private String safeToolPid;
    private String riskyToolPid;

    // ========== Setup: create agent definitions and their tools ==========

    @BeforeEach
    void setup() {
        if (safeAgentPid != null && riskyAgentPid != null) {
            return;
        }
        Long tenantId = getTestTenant().getId();
        LocalDateTime now = LocalDateTime.now();

        // --- Safe agent (no approval-required tools) ---
        safeAgentPid = UniqueIdGenerator.generate();
        Map<String, Object> safeAgent = new HashMap<>();
        safeAgent.put("pid", safeAgentPid);
        safeAgent.put("tenant_id", tenantId);
        safeAgent.put("agent_code", safeAgentCode);
        safeAgent.put("name", "Safe Test Agent " + suffix);
        safeAgent.put("agent_type", "reactive");
        safeAgent.put("status", "active");
        safeAgent.put("created_at", now);
        safeAgent.put("updated_at", now);
        // tools JSON: explicitly bound to a single safe tool code
        safeAgent.put("tools", "[\"safe-read-tool-" + suffix + "\"]");
        dynamicDataMapper.insert("ab_agent_definition", safeAgent);

        // Safe tool: requires_approval = false
        safeToolPid = UniqueIdGenerator.generate();
        Map<String, Object> safeTool = new HashMap<>();
        safeTool.put("pid", safeToolPid);
        safeTool.put("tenant_id", tenantId);
        safeTool.put("tool_code", "safe-read-tool-" + suffix);
        safeTool.put("tool_type", "builtin");
        safeTool.put("tool_name", "Safe Read Tool " + suffix);
        safeTool.put("tool_description", "Read-only tool for testing");
        safeTool.put("requires_approval", false);
        safeTool.put("risk_level", "low");
        safeTool.put("tool_status", "active");
        safeTool.put("created_at", now);
        safeTool.put("updated_at", now);
        dynamicDataMapper.insert("ab_agent_tool", safeTool);

        // --- Risky agent (has one approval-required tool) ---
        riskyAgentPid = UniqueIdGenerator.generate();
        Map<String, Object> riskyAgent = new HashMap<>();
        riskyAgent.put("pid", riskyAgentPid);
        riskyAgent.put("tenant_id", tenantId);
        riskyAgent.put("agent_code", riskyAgentCode);
        riskyAgent.put("name", "Risky Test Agent " + suffix);
        riskyAgent.put("agent_type", "reactive");
        riskyAgent.put("status", "active");
        riskyAgent.put("created_at", now);
        riskyAgent.put("updated_at", now);
        // tools JSON: explicitly bound to a risky tool that requires approval
        riskyAgent.put("tools", "[\"risky-delete-tool-" + suffix + "\"]");
        dynamicDataMapper.insert("ab_agent_definition", riskyAgent);

        // Risky tool: requires_approval = true
        riskyToolPid = UniqueIdGenerator.generate();
        Map<String, Object> riskyTool = new HashMap<>();
        riskyTool.put("pid", riskyToolPid);
        riskyTool.put("tenant_id", tenantId);
        riskyTool.put("tool_code", "risky-delete-tool-" + suffix);
        riskyTool.put("tool_type", "builtin");
        riskyTool.put("tool_name", "Risky Delete Tool " + suffix);
        riskyTool.put("tool_description", "Destructive tool requiring approval");
        riskyTool.put("requires_approval", true);
        riskyTool.put("risk_level", "high");
        riskyTool.put("tool_status", "active");
        riskyTool.put("created_at", now);
        riskyTool.put("updated_at", now);
        dynamicDataMapper.insert("ab_agent_tool", riskyTool);
    }

    // ========== Test 1: safe agent — no approval-required tools ==========

    @Test
    @Order(1)
    void agentHasApprovalRequiredTools_safeAgent_returnsFalse() {
        Long tenantId = getTestTenant().getId();

        boolean result = scheduleService.agentHasApprovalRequiredTools(tenantId, safeAgentCode);

        assertThat(result)
                .as("Safe agent with no approval-required tools should return false")
                .isFalse();
    }

    // ========== Test 2: risky agent — has approval-required tools ==========

    @Test
    @Order(2)
    void agentHasApprovalRequiredTools_riskyAgent_returnsTrue() {
        Long tenantId = getTestTenant().getId();

        boolean result = scheduleService.agentHasApprovalRequiredTools(tenantId, riskyAgentCode);

        assertThat(result)
                .as("Risky agent with approval-required tools should return true")
                .isTrue();
    }

    // ========== Test 3: unknown agent — returns false (no tools loaded) ==========

    @Test
    @Order(3)
    void agentHasApprovalRequiredTools_unknownAgent_returnsFalse() {
        Long tenantId = getTestTenant().getId();

        boolean result = scheduleService.agentHasApprovalRequiredTools(tenantId, "nonexistent-agent-" + suffix);

        assertThat(result)
                .as("Unknown agent with no tools should return false (safe default)")
                .isFalse();
    }

    // ========== Test 4: risky tool disabled — does not count as requiring approval ==========

    @Test
    @Order(4)
    void agentHasApprovalRequiredTools_toolDisabled_returnsFalse() {
        Long tenantId = getTestTenant().getId();
        LocalDateTime now = LocalDateTime.now();

        // Disable the risky tool
        dynamicDataMapper.update("ab_agent_tool",
                Map.of("tool_status", "inactive", "updated_at", now),
                Map.of("pid", riskyToolPid));

        boolean result = scheduleService.agentHasApprovalRequiredTools(tenantId, riskyAgentCode);

        // Restore
        dynamicDataMapper.update("ab_agent_tool",
                Map.of("tool_status", "active", "updated_at", now),
                Map.of("pid", riskyToolPid));

        assertThat(result)
                .as("Inactive tool should not trigger approval gate")
                .isFalse();
    }
}
