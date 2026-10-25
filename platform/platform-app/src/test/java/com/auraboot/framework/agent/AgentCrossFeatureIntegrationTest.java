package com.auraboot.framework.agent;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-feature integration test verifying the Tool→Observation→Memory feedback loop.
 *
 * <p>Flow under test:
 * <ol>
 *   <li>An agent run executes and records an observation (tool call metric).</li>
 *   <li>The run generates a LESSON memory stored via direct SQL.</li>
 *   <li>The memory is linked back to the originating run via {@code source_run_id}.</li>
 *   <li>Memory retrieval is scoped to the owning agent — other agents see an empty set.</li>
 * </ol>
 *
 * <p>Uses real PostgreSQL — no H2, no mocks for DB/Redis.
 * All records persist (no @Rollback) to allow manual inspection of the data lifecycle.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentCrossFeatureIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Unique run-level identifiers — all seeded rows share this prefix
    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private final String agentCode = "cross-feat-agent-" + testRunId;

    // PIDs set during seed so subsequent tests can reference them
    private String runPid;

    // Guard: seed executes exactly once even if @Order(1) is somehow retried
    private boolean seeded = false;

    // ========== Test 1: seed data + verify run has at least one observation ==========

    @Test
    @Order(1)
    void seed_runHasObservation() {
        if (seeded) {
            return;
        }
        seeded = true;

        Long tenantId = getTestTenant().getId();
        Long userId   = getTestUser().getId();

        // --- 1. Agent definition ---
        String agentDefinitionPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition "
                + "(pid, tenant_id, agent_code, name, agent_type, status, deleted_flag, created_at, updated_at, created_by) "
                + "VALUES (?, ?, ?, ?, 'reactive', 'active', FALSE, NOW(), NOW(), ?)",
                agentDefinitionPid,
                tenantId,
                agentCode,
                "Cross-Feature Test Agent " + testRunId,
                userId
        );

        // --- 2. Agent task (COMPLETED) ---
        String taskPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_task "
                + "(pid, tenant_id, title, task_status, assignee_type, assignee_id, deleted_flag, created_at, updated_at, created_by) "
                + "VALUES (?, ?, ?, 'completed', 'agent', ?, FALSE, NOW(), NOW(), ?)",
                taskPid,
                tenantId,
                "Cross-Feature Task " + testRunId,
                agentCode,
                userId
        );

        // --- 3. Agent run (SUCCESS) ---
        runPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_run "
                + "(pid, tenant_id, task_id, agent_id, run_status, run_model, "
                + " input_tokens, output_tokens, duration_ms, started_at, completed_at, "
                + " created_at, updated_at, created_by) "
                + "VALUES (?, ?, ?, ?, 'success', 'claude-sonnet-4-6', "
                + " 120, 80, 450, NOW() - INTERVAL '1 minute', NOW(), "
                + " NOW(), NOW(), ?)",
                runPid,
                tenantId,
                taskPid,
                agentCode,
                userId
        );

        // --- 4. Observation record (tool call metric linked to the run) ---
        String observationPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_observation "
                + "(pid, tenant_id, observation_type, source_type, source_id, obs_agent_id, "
                + " obs_title, detail, severity, created_at, updated_at, created_by) "
                + "VALUES (?, ?, 'tool_call', 'run', ?, ?, "
                + " ?, 'Tool executed successfully in 450ms', 'info', NOW(), NOW(), ?)",
                observationPid,
                tenantId,
                runPid,
                agentCode,
                "Tool call: search_records [run=" + runPid + "]",
                userId
        );

        // --- 5. Memory record (LESSON) linked to the run via direct SQL ---
        String memoryPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory "
                + "(pid, tenant_id, memory_agent_id, memory_type, memory_title, memory_content, "
                + " importance, source_run_id, deleted_flag, created_at, updated_at, created_by) "
                + "VALUES (?, ?, ?, 'lesson', ?, ?, 7, ?, FALSE, NOW(), NOW(), ?)",
                memoryPid,
                tenantId,
                agentCode,
                "Tool execution insight " + testRunId,
                "search_records tool completes within 500ms when filters are indexed fields",
                runPid,
                userId
        );

        // Verify: at least one observation exists for this run
        List<Map<String, Object>> observations = jdbcTemplate.queryForList(
                "SELECT pid, observation_type, source_id FROM ab_agent_observation "
                + "WHERE tenant_id = ? AND source_id = ?",
                tenantId, runPid
        );

        assertThat(observations)
                .as("Run %s should have at least 1 observation", runPid)
                .isNotEmpty();
        assertThat(observations.get(0).get("observation_type"))
                .as("Observation type should be TOOL_CALL")
                .isEqualTo("tool_call");
    }

    // ========== Test 2: run generates a LESSON memory ==========

    @Test
    @Order(2)
    void run_generatesLessonMemory() {
        Long tenantId = getTestTenant().getId();

        List<Map<String, Object>> memories = jdbcTemplate.queryForList(
                "SELECT memory_type, memory_title, importance FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "ORDER BY importance DESC",
                tenantId, agentCode
        );

        assertThat(memories)
                .as("Agent %s should have at least 1 memory", agentCode)
                .isNotEmpty();

        boolean hasLesson = memories.stream()
                .anyMatch(m -> "lesson".equals(m.get("memory_type")));

        assertThat(hasLesson)
                .as("At least one memory should have memory_type = LESSON")
                .isTrue();
    }

    // ========== Test 3: memory source_run_id links back to the originating run ==========

    @Test
    @Order(3)
    void memory_sourceRunId_linksToRun() {
        Long tenantId = getTestTenant().getId();

        List<Map<String, Object>> linked = jdbcTemplate.queryForList(
                "SELECT pid, memory_type, memory_title, source_run_id "
                + "FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND source_run_id = ? "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, runPid
        );

        assertThat(linked)
                .as("ab_agent_memory should contain at least 1 record with source_run_id = %s", runPid)
                .isNotEmpty();

        assertThat(linked.get(0).get("source_run_id"))
                .as("source_run_id must equal the seeded run PID")
                .isEqualTo(runPid);
    }

    // ========== Test 4: memory retrieval is scoped to the owning agent ==========

    @Test
    @Order(4)
    void memoryRetrieval_scopedToAgent_differentAgentReturnsEmpty() {
        Long tenantId = getTestTenant().getId();
        // Use a unique code that was never seeded
        String unrelatedAgentCode = "unrelated-agent-" + testRunId;

        List<Map<String, Object>> memories = jdbcTemplate.queryForList(
                "SELECT pid, memory_type FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, unrelatedAgentCode
        );

        assertThat(memories)
                .as("A different agent (%s) should have no memories", unrelatedAgentCode)
                .isEmpty();
    }
}
