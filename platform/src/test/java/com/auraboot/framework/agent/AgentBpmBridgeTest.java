package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentBpmBridge;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AgentBpmBridge.
 * Covers BPM → Agent delegation (task creation with _bpm_* variables),
 * poll-by-PID, graceful handling of missing BPM definitions,
 * and pollWithBackoff timeout behaviour.
 * Uses real database, no mocking. Data persists (no rollback).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentBpmBridgeTest extends BaseIntegrationTest {

    @Autowired
    private AgentBpmBridge agentBpmBridge;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private String bridgeAgentCode;

    // ========== Setup: create a test agent definition for BPM delegation ==========
    // NOTE: @BeforeEach instead of @BeforeAll because BaseIntegrationTest.setupTenantContext()
    // (which sets testTenant) is a @BeforeEach and runs before this, giving us the tenant ID.
    // The try-catch handles duplicate inserts gracefully across repeated @BeforeEach calls.

    @BeforeEach
    void setUpBridgeAgent() {
        Long tenantId = getTestTenant().getId();
        bridgeAgentCode = "bpm-bridge-agent-" + testRunId;

        Map<String, Object> agent = new HashMap<>();
        agent.put("pid", UniqueIdGenerator.generate());
        agent.put("tenant_id", tenantId);
        agent.put("agent_code", bridgeAgentCode);
        agent.put("name", "BPM Bridge Test Agent");
        agent.put("agent_type", "reactive");
        agent.put("status", "active");
        agent.put("deleted_flag", false);
        agent.put("created_at", LocalDateTime.now());
        agent.put("updated_at", LocalDateTime.now());
        try {
            dynamicDataMapper.insert("ab_agent_definition", agent);
        } catch (Exception e) {
            // Already exists from a previous run — ignore
        }
    }

    // ========== Test 1: delegateToAgent creates a task with _bpm_* context ==========

    @Test
    @Order(1)
    void delegateToAgent_createsTaskWithBpmContext() {
        Long tenantId = getTestTenant().getId();
        String processInstanceId = "proc-inst-" + testRunId;
        String activityId = "service-task-01";

        String taskPid = agentBpmBridge.delegateToAgent(
                tenantId,
                processInstanceId,
                activityId,
                bridgeAgentCode,
                "BPM-Delegated Task " + testRunId,
                "Process the approval workflow step",
                Map.of("applicant", "alice", "amount", 1000)
        );

        assertNotNull(taskPid, "delegateToAgent must return a task PID");

        // Verify task row exists with BPM context in input_data
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT task_status, assignee_id, input_data FROM ab_agent_task "
                + "WHERE pid = ? AND tenant_id = ?",
                taskPid, tenantId);

        assertFalse(rows.isEmpty(), "Delegated task must exist in DB");
        assertEquals(bridgeAgentCode, rows.get(0).get("assignee_id"),
                "Task must be assigned to the bridge agent");

        String inputData = (String) rows.get(0).get("input_data");
        assertNotNull(inputData, "input_data must not be null");
        assertTrue(inputData.contains("_bpm_process_id"),
                "input_data must contain _bpm_process_id key");
        assertTrue(inputData.contains("_bpm_activity_id"),
                "input_data must contain _bpm_activity_id key");
        assertTrue(inputData.contains("_bpm_delegated"),
                "input_data must contain _bpm_delegated flag");
        // Context variables stored with _bpm_variables. prefix
        assertTrue(inputData.contains("_bpm_variables.applicant"),
                "input_data must include _bpm_variables.applicant from contextData");
    }

    // ========== Test 2: pollAgentTaskStatus returns found=false for a non-existent PID ==========

    @Test
    @Order(2)
    void pollAgentTaskStatus_returnsNotFoundForInvalidPid() {
        Long tenantId = getTestTenant().getId();
        String nonExistentPid = "nonexistent-task-pid-" + testRunId;

        Map<String, Object> result = agentBpmBridge.pollAgentTaskStatus(tenantId, nonExistentPid);

        assertNotNull(result, "pollAgentTaskStatus must not return null");
        assertEquals(false, result.get("found"),
                "found must be false for a non-existent task PID");
        assertEquals(nonExistentPid, result.get("taskPid"),
                "taskPid in result must match the queried PID");
    }

    // ========== Test 3: startBpmProcess handles a missing BPM process definition gracefully ==========

    @Test
    @Order(3)
    void startBpmProcess_handlesMissingDefinition() {
        Long tenantId = getTestTenant().getId();

        Map<String, Object> result = agentBpmBridge.startBpmProcess(
                tenantId,
                "agent-run-" + testRunId,
                "nonexistent-bpm-process-code",
                Map.of("param", "value")
        );

        assertNotNull(result, "startBpmProcess must not return null for missing definition");
        assertEquals(false, result.get("success"),
                "success must be false when the BPM process definition does not exist");
        assertNotNull(result.get("error"),
                "error message must be present when the process definition is not found");
    }

    // ========== Test 4: pollWithBackoff times out gracefully for a non-existent task ==========

    @Test
    @Order(4)
    void pollWithBackoff_timesOutGracefully() {
        Long tenantId = getTestTenant().getId();
        String nonExistentPid = "backoff-task-" + testRunId;

        // 1-second timeout — should return TIMEOUT quickly since the task doesn't exist
        long start = System.currentTimeMillis();
        Map<String, Object> result = agentBpmBridge.pollWithBackoff(tenantId, nonExistentPid, 1);
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(result, "pollWithBackoff must not return null on timeout");
        assertEquals("timeout", result.get("status"),
                "status must be TIMEOUT when the task never completes within the deadline");
        assertEquals(nonExistentPid, result.get("taskPid"),
                "taskPid must be echoed back in the timeout result");
        // Should not have waited dramatically longer than the 1-second timeout
        assertTrue(elapsed < 15_000,
                "pollWithBackoff should not block much longer than maxWaitSeconds (elapsed=" + elapsed + "ms)");
    }

    // ========== Test 5: variableMapping uses standardized _bpm_* prefix ==========

    @Test
    @Order(5)
    void variableMapping_usesBpmPrefix() {
        Long tenantId = getTestTenant().getId();
        String processId = "proc-var-test-" + testRunId;

        String taskPid = agentBpmBridge.delegateToAgent(
                tenantId,
                processId,
                "activity-A",
                bridgeAgentCode,
                "Variable Mapping Test Task " + testRunId,
                "Verify _bpm_ variable prefix convention",
                Map.of("orderRef", "ORD-001", "region", "apac"),
                0  // no timeout
        );

        assertNotNull(taskPid, "Task PID must be returned");

        // Load raw input_data from DB and confirm all _bpm_ prefixes are present
        String inputData = jdbcTemplate.queryForObject(
                "SELECT input_data FROM ab_agent_task WHERE pid = ? AND tenant_id = ?",
                String.class, taskPid, tenantId);

        assertNotNull(inputData, "input_data must be stored");
        assertTrue(inputData.contains("\"_bpm_process_id\""),
                "Must contain _bpm_process_id");
        assertTrue(inputData.contains("\"_bpm_activity_id\""),
                "Must contain _bpm_activity_id");
        assertTrue(inputData.contains("\"_bpm_delegated\""),
                "Must contain _bpm_delegated");
        // Context data prefixed as _bpm_variables.<key>
        assertTrue(inputData.contains("_bpm_variables.orderRef"),
                "Context variable 'orderRef' must be stored under _bpm_variables.orderRef");
        assertTrue(inputData.contains("_bpm_variables.region"),
                "Context variable 'region' must be stored under _bpm_variables.region");
    }

    // ========== Test 6: delegateToAgent with timeout stores _bpm_timeout_seconds ==========

    @Test
    @Order(6)
    void delegateToAgent_withTimeout_storesTimeoutVariable() {
        Long tenantId = getTestTenant().getId();

        String taskPid = agentBpmBridge.delegateToAgent(
                tenantId,
                "proc-timeout-" + testRunId,
                "activity-timeout",
                bridgeAgentCode,
                "Timeout Test Task " + testRunId,
                "Test that timeout seconds are stored",
                null,
                120  // 2-minute timeout
        );

        assertNotNull(taskPid);

        String inputData = jdbcTemplate.queryForObject(
                "SELECT input_data FROM ab_agent_task WHERE pid = ? AND tenant_id = ?",
                String.class, taskPid, tenantId);

        assertNotNull(inputData);
        assertTrue(inputData.contains("_bpm_timeout_seconds"),
                "input_data must contain _bpm_timeout_seconds when timeoutSeconds > 0");
        assertTrue(inputData.contains("120"),
                "The stored timeout value must match the specified 120 seconds");
    }
}
