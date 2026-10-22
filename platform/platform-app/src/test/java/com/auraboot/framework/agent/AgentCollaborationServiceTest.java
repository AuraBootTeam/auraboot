package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentCollaborationService;
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
 * Integration tests for AgentCollaborationService.
 * Covers BROADCAST, PIPELINE, and DELEGATE collaboration modes,
 * delegation timeout sweeper, and child-task structure verification.
 * Uses real database, no mocking. Data persists (no rollback).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentCollaborationServiceTest extends BaseIntegrationTest {

    @Autowired
    private AgentCollaborationService agentCollaborationService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private String parentTaskPid;
    private String agent1Code;
    private String agent2Code;

    // ========== Setup: create 2 agent definitions and a parent task ==========
    // NOTE: @BeforeEach instead of @BeforeAll because BaseIntegrationTest.setupTenantContext()
    // (which sets testTenant) is a @BeforeEach and runs before this, giving us the tenant ID.
    // The try-catch handles duplicate inserts gracefully across repeated @BeforeEach calls.

    @BeforeEach
    void setUpAgentsAndParentTask() {
        Long tenantId = getTestTenant().getId();
        agent1Code = "collab-agent-A-" + testRunId;
        agent2Code = "collab-agent-B-" + testRunId;

        // Insert two minimal agent definitions
        for (String code : List.of(agent1Code, agent2Code)) {
            Map<String, Object> agent = new HashMap<>();
            agent.put("pid", UniqueIdGenerator.generate());
            agent.put("tenant_id", tenantId);
            agent.put("agent_code", code);
            agent.put("name", "Collaboration Test Agent " + code);
            agent.put("agent_type", "reactive");
            agent.put("status", "active");
            agent.put("deleted_flag", false);
            agent.put("created_at", LocalDateTime.now());
            agent.put("updated_at", LocalDateTime.now());
            try {
                dynamicDataMapper.insert("ab_agent_definition", agent);
            } catch (Exception e) {
                // May already exist from a previous run — ignore
            }
        }

        // Insert parent task (try-catch handles duplicate on repeated @BeforeEach calls)
        parentTaskPid = "parent-task-" + testRunId;
        Map<String, Object> parentTask = new HashMap<>();
        parentTask.put("pid", parentTaskPid);
        parentTask.put("tenant_id", tenantId);
        parentTask.put("title", "Parent Task for Collaboration Test");
        parentTask.put("task_status", "in_progress");
        parentTask.put("assignee_type", "human");
        parentTask.put("created_at", LocalDateTime.now());
        parentTask.put("updated_at", LocalDateTime.now());
        parentTask.put("deleted_flag", false);
        try {
            dynamicDataMapper.insert("ab_agent_task", parentTask);
        } catch (Exception e) {
            // May already exist from a previous @BeforeEach call — ignore
        }
    }

    // ========== Test 1: broadcastTask dispatches to multiple agents ==========

    @Test
    @Order(1)
    void broadcastTask_dispatchesToMultipleAgents() {
        Long tenantId = getTestTenant().getId();

        Map<String, Object> result = agentCollaborationService.broadcastTask(
                tenantId,
                parentTaskPid,
                List.of(agent1Code, agent2Code),
                "Broadcast test task " + testRunId,
                Map.of("key", "value"),
                30
        );

        assertNotNull(result, "broadcastTask must return a result map");
        assertEquals("broadcast", result.get("mode"), "mode should be BROADCAST");
        assertEquals(2, result.get("totalAgents"), "totalAgents should match the number of agents");

        @SuppressWarnings("unchecked")
        List<String> childPids = (List<String>) result.get("childTaskPids");
        assertNotNull(childPids, "childTaskPids must be present");
        assertEquals(2, childPids.size(), "Should have created 2 child task PIDs");

        // Verify both child tasks exist in DB
        for (String pid : childPids) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT task_status FROM ab_agent_task WHERE pid = ? AND tenant_id = ?",
                    pid, tenantId);
            assertFalse(rows.isEmpty(), "Child task " + pid + " must exist in DB");
        }
    }

    // ========== Test 2: scoreBroadcastResults returns NO_RESULTS for non-existent parent ==========

    @Test
    @Order(2)
    void scoreBroadcastResults_handlesNoResults() {
        Long tenantId = getTestTenant().getId();
        String nonExistentPid = "no-children-parent-" + testRunId;

        Map<String, Object> result = agentCollaborationService.scoreBroadcastResults(
                tenantId, nonExistentPid);

        assertNotNull(result, "scoreBroadcastResults should not return null");
        assertEquals("no_results", result.get("status"),
                "Should return NO_RESULTS when no child tasks exist for the parent");
    }

    // ========== Test 3: pipelineTask with empty steps does not NPE ==========

    @Test
    @Order(3)
    void pipelineTask_emptySteps_completesGracefully() {
        Long tenantId = getTestTenant().getId();

        // Empty steps list: pipeline has nothing to execute → should complete immediately
        assertDoesNotThrow(() -> {
            Map<String, Object> result = agentCollaborationService.pipelineTask(
                    tenantId,
                    parentTaskPid,
                    List.of(),           // no steps
                    Map.of("init", "data")
            );
            assertNotNull(result, "pipelineTask with empty steps must return a result");
            // Should complete because the loop body never executes
            assertEquals("completed", result.get("status"),
                    "An empty pipeline should immediately complete");
        }, "pipelineTask with empty steps must not throw");
    }

    // ========== Test 4: delegateTask — existing DELEGATE mode still works ==========

    @Test
    @Order(4)
    void delegateTask_existingFunctionality() {
        Long tenantId = getTestTenant().getId();

        String childPid = agentCollaborationService.delegateTask(
                tenantId,
                parentTaskPid,
                "run-" + testRunId,
                agent1Code,
                "Delegate Test Sub-task " + testRunId,
                "Description for delegate test",
                Map.of("key", "delegateValue")
        );

        assertNotNull(childPid, "delegateTask must return a child task PID");

        // Verify child task created with correct parent link
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT parent_id, assignee_id FROM ab_agent_task WHERE pid = ? AND tenant_id = ?",
                childPid, tenantId);
        assertFalse(rows.isEmpty(), "Delegated child task must exist in DB");
        assertEquals(parentTaskPid, rows.get(0).get("parent_id"),
                "Child task must reference the correct parent task PID");
        assertEquals(agent1Code, rows.get(0).get("assignee_id"),
                "Child task must be assigned to the target agent");
    }

    // ========== Test 5: checkDelegationTimeouts does not throw when no timed-out tasks ==========

    @Test
    @Order(5)
    void checkDelegationTimeouts_noErrorWhenNoTimedOut() {
        // Directly invoke the scheduled method — must not throw
        assertDoesNotThrow(
                () -> agentCollaborationService.checkDelegationTimeouts(),
                "checkDelegationTimeouts must not throw even if there are no stale tasks"
        );
    }

    // ========== Test 6: getChildTaskStatuses returns created children ==========

    @Test
    @Order(6)
    void getChildTaskStatuses_returnsCreatedChildren() {
        Long tenantId = getTestTenant().getId();

        // Test 1 created 2 children via broadcast, test 4 created 1 more
        List<Map<String, Object>> children = agentCollaborationService
                .getChildTaskStatuses(tenantId, parentTaskPid);

        assertTrue(children.size() >= 3,
                "Should have at least 3 child tasks (2 from broadcast + 1 from delegate)");
    }

    // ========== Test 7: checkDelegationComplete reflects running children ==========

    @Test
    @Order(7)
    void checkDelegationComplete_reflectsRunningChildren() {
        Long tenantId = getTestTenant().getId();

        Map<String, Object> summary = agentCollaborationService
                .checkDelegationComplete(tenantId, parentTaskPid);

        assertNotNull(summary, "checkDelegationComplete must return a summary map");
        assertTrue(summary.containsKey("allDone"), "Summary must contain allDone flag");
        assertTrue(summary.containsKey("totalChildren"), "Summary must contain totalChildren count");
        int total = ((Number) summary.get("totalChildren")).intValue();
        assertTrue(total >= 3, "Total children should reflect all delegated tasks");
    }
}
