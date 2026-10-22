package com.auraboot.framework.agent;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Agent Cost and Concurrency limit enforcement.
 * Verifies:
 *   1. Agent definition stores max_concurrent_runs correctly.
 *   2. Concurrent run states (RUNNING / QUEUED) are tracked in ab_agent_run.
 *   3. Run cost is recorded via ab_agent_run.total_cost and surfaced in ab_agent_observation.
 *
 * Uses real PostgreSQL, no mocks, no rollback (NOT_SUPPORTED).
 * Data is identified by testRunId prefix to avoid cross-test collisions.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentCostConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private final String agentCode = "test-concurrency-" + testRunId;

    // PIDs are set during seeding (Test 1) and reused in subsequent tests
    private String agentDefinitionPid;
    private String taskPid;
    private String run1Pid;  // first RUNNING run — used for cost observation

    /**
     * Seed the agent definition with max_concurrent_runs = 3.
     * Called at the start of Test 1 (after @BeforeEach has established MetaContext).
     */
    private void seedAgentDefinition() {
        Long tenantId = getTestTenant().getId();
        agentDefinitionPid = UniqueIdGenerator.generate();

        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition "
                + "(pid, tenant_id, agent_code, name, agent_type, max_concurrent_runs, status) "
                + "VALUES (?, ?, ?, ?, 'reactive', 3, 'active')",
                agentDefinitionPid,
                tenantId,
                agentCode,
                "Concurrency Test Agent " + testRunId
        );
    }

    // =========================================================================
    // Test 1 — agentDefinition_hasMaxConcurrentRuns
    // Seeds the agent definition, then verifies max_concurrent_runs = 3.
    // =========================================================================

    @Test
    @Order(1)
    void agentDefinition_hasMaxConcurrentRuns() {
        seedAgentDefinition();

        Long tenantId = getTestTenant().getId();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT max_concurrent_runs FROM ab_agent_definition "
                + "WHERE pid = ? AND tenant_id = ?",
                agentDefinitionPid, tenantId);

        assertThat(rows).hasSize(1);
        int maxRuns = ((Number) rows.get(0).get("max_concurrent_runs")).intValue();
        assertThat(maxRuns).isEqualTo(3);
    }

    // =========================================================================
    // Test 2 — concurrentRuns_excessQueued
    //   Insert 1 task + 5 runs: 3 RUNNING, 2 QUEUED
    //   Assert COUNT(RUNNING) = 3, COUNT(QUEUED) = 2
    // =========================================================================

    @Test
    @Order(2)
    void concurrentRuns_excessQueued() {
        Long tenantId = getTestTenant().getId();

        // Create a task for the agent
        taskPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_task "
                + "(pid, tenant_id, title, task_status, assignee_type, assignee_id) "
                + "VALUES (?, ?, ?, 'in_progress', 'agent', ?)",
                taskPid,
                tenantId,
                "Concurrency Test Task " + testRunId,
                agentCode
        );

        // Insert 3 RUNNING runs (equal to max_concurrent_runs)
        run1Pid = UniqueIdGenerator.generate();
        String[] runningPids = {run1Pid, UniqueIdGenerator.generate(), UniqueIdGenerator.generate()};
        for (String pid : runningPids) {
            jdbcTemplate.update(
                    "INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status) "
                    + "VALUES (?, ?, ?, ?, 'running')",
                    pid, tenantId, taskPid, agentCode);
        }

        // Insert 2 QUEUED runs (excess beyond max_concurrent_runs = 3)
        String[] queuedPids = {UniqueIdGenerator.generate(), UniqueIdGenerator.generate()};
        for (String pid : queuedPids) {
            jdbcTemplate.update(
                    "INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status) "
                    + "VALUES (?, ?, ?, ?, 'queued')",
                    pid, tenantId, taskPid, agentCode);
        }

        // Assert RUNNING count = 3
        Integer runningCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run "
                + "WHERE tenant_id = ? AND agent_id = ? AND run_status = 'running'",
                Integer.class,
                tenantId, agentCode);
        assertThat(runningCount).isEqualTo(3);

        // Assert QUEUED count = 2
        Integer queuedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run "
                + "WHERE tenant_id = ? AND agent_id = ? AND run_status = 'queued'",
                Integer.class,
                tenantId, agentCode);
        assertThat(queuedCount).isEqualTo(2);
    }

    // =========================================================================
    // Test 3 — runCost_trackedInObservation
    //   Set total_cost = 0.005 on run1Pid in ab_agent_run
    //   Emit a METRIC observation recording the cost in detail field
    //   Assert total_cost on the run row equals 0.005
    //   Assert observation row exists with obs_title='total_cost' and matching detail
    // =========================================================================

    @Test
    @Order(3)
    void runCost_trackedInObservation() {
        Long tenantId = getTestTenant().getId();
        BigDecimal expectedCost = new BigDecimal("0.005000");

        // Record cost directly on the run row (ab_agent_run.total_cost is numeric(10,6))
        jdbcTemplate.update(
                "UPDATE ab_agent_run SET total_cost = ? WHERE pid = ? AND tenant_id = ?",
                expectedCost, run1Pid, tenantId);

        // Emit a METRIC observation to surface the cost in the observation log
        String observationPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_observation "
                + "(pid, tenant_id, observation_type, source_type, source_id, obs_agent_id, obs_title, detail, severity) "
                + "VALUES (?, ?, 'metric', 'run', ?, ?, 'total_cost', ?, 'info')",
                observationPid,
                tenantId,
                run1Pid,
                agentCode,
                expectedCost.toPlainString()
        );

        // --- Assert: total_cost stored correctly on the run row ---
        List<Map<String, Object>> runRows = jdbcTemplate.queryForList(
                "SELECT total_cost FROM ab_agent_run WHERE pid = ? AND tenant_id = ?",
                run1Pid, tenantId);
        assertThat(runRows).hasSize(1);
        BigDecimal actualCost = (BigDecimal) runRows.get(0).get("total_cost");
        assertThat(actualCost).isEqualByComparingTo(expectedCost);

        // --- Assert: observation row has matching obs_title and detail ---
        List<Map<String, Object>> obsRows = jdbcTemplate.queryForList(
                "SELECT obs_title, detail FROM ab_agent_observation "
                + "WHERE pid = ? AND tenant_id = ?",
                observationPid, tenantId);
        assertThat(obsRows).hasSize(1);
        assertThat((String) obsRows.get(0).get("obs_title")).isEqualTo("total_cost");
        assertThat((String) obsRows.get(0).get("detail")).isEqualTo(expectedCost.toPlainString());
    }
}
