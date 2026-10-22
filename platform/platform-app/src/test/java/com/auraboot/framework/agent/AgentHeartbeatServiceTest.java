package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentHeartbeatService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for AgentHeartbeatService.
 *
 * <p>Uses NOT_SUPPORTED propagation to commit test data before querying,
 * matching the pattern in AgentScheduleIntegrationTest.
 *
 * <p>Each test inserts its own data with a unique run-id prefix so tests
 * are independent of each other and of pre-existing data.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentHeartbeatServiceTest extends BaseIntegrationTest {

    @Autowired
    private AgentHeartbeatService heartbeatService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String runId = String.valueOf(System.currentTimeMillis() % 100000000L);

    // ------------------------------------------------------------------ //
    //  Test 1: runHeartbeat returns all required keys                      //
    // ------------------------------------------------------------------ //

    @Test
    @Order(1)
    void runHeartbeat_returnsAllRequiredKeys() {
        Long tenantId = getTestTenant().getId();

        Map<String, Object> report = heartbeatService.runHeartbeat(tenantId);

        assertThat(report).containsKeys(
                "timeout_approvals",
                "stale_tasks",
                "recent_failures",
                "memory_overloaded_agents",
                "healthy",
                "checked_at"
        );
        assertThat(report.get("checked_at")).isNotNull();
    }

    // ------------------------------------------------------------------ //
    //  Test 2: healthy=true when no issues                                 //
    // ------------------------------------------------------------------ //

    @Test
    @Order(2)
    void runHeartbeat_healthyTrue_whenNoIssues() {
        Long tenantId = getTestTenant().getId();

        Map<String, Object> report = heartbeatService.runHeartbeat(tenantId);

        int timeoutApprovals = ((Number) report.get("timeout_approvals")).intValue();
        int staleTasks = ((Number) report.get("stale_tasks")).intValue();
        int failures = ((Number) report.get("recent_failures")).intValue();
        boolean healthy = (Boolean) report.get("healthy");

        assertThat(healthy).isEqualTo(
                timeoutApprovals == 0
                        && staleTasks == 0
                        && failures <= 2
        );
    }

    // ------------------------------------------------------------------ //
    //  Test 3: timeout_approvals > 0 when stale PENDING approval exists    //
    // ------------------------------------------------------------------ //

    @Test
    @Order(3)
    void runHeartbeat_reportsTimeoutApproval_whenStaleApprovalExists() {
        Long tenantId = getTestTenant().getId();
        String pid = "hb-appr-" + runId;

        // Insert a PENDING approval older than 24 hours
        jdbcTemplate.update(
                "INSERT INTO ab_agent_approval " +
                "(pid, tenant_id, approval_type, approval_title, approval_status, created_at, updated_at) " +
                "VALUES (?, ?, 'tool_call', 'Stale approval " + runId + "', 'pending', " +
                "NOW() - INTERVAL '25 hours', NOW() - INTERVAL '25 hours')",
                pid, tenantId);

        try {
            int timeoutApprovals = heartbeatService.countTimeoutApprovals(tenantId);
            assertThat(timeoutApprovals).isGreaterThan(0);

            Map<String, Object> report = heartbeatService.runHeartbeat(tenantId);
            assertThat(((Number) report.get("timeout_approvals")).intValue()).isGreaterThan(0);
            assertThat(report.get("healthy")).isEqualTo(false);
        } finally {
            // Clean up: delete the stale approval we inserted
            jdbcTemplate.update("DELETE FROM ab_agent_approval WHERE pid = ?", pid);
        }
    }

    // ------------------------------------------------------------------ //
    //  Test 4: recent_failures count matches inserted FAILED runs          //
    // ------------------------------------------------------------------ //

    @Test
    @Order(4)
    void runHeartbeat_reportsRecentFailures_whenFailedRunsExist() {
        Long tenantId = getTestTenant().getId();
        String taskPid = "hb-task-" + runId;
        String run1Pid = "hb-run1-" + runId;
        String run2Pid = "hb-run2-" + runId;
        String run3Pid = "hb-run3-" + runId;

        // Insert a task so foreign-key (if any) constraints are satisfied
        // ab_agent_run.task_id is VARCHAR(26) and has no FK in schema, so we can insert directly
        // Insert 3 FAILED runs within the last hour
        jdbcTemplate.update(
                "INSERT INTO ab_agent_run " +
                "(pid, tenant_id, task_id, agent_id, run_status, started_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'failed', NOW() - INTERVAL '10 minutes', " +
                "NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes')",
                run1Pid, tenantId, taskPid, "hb-agent-" + runId);

        jdbcTemplate.update(
                "INSERT INTO ab_agent_run " +
                "(pid, tenant_id, task_id, agent_id, run_status, started_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'failed', NOW() - INTERVAL '20 minutes', " +
                "NOW() - INTERVAL '20 minutes', NOW() - INTERVAL '20 minutes')",
                run2Pid, tenantId, taskPid, "hb-agent-" + runId);

        jdbcTemplate.update(
                "INSERT INTO ab_agent_run " +
                "(pid, tenant_id, task_id, agent_id, run_status, started_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'failed', NOW() - INTERVAL '30 minutes', " +
                "NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '30 minutes')",
                run3Pid, tenantId, taskPid, "hb-agent-" + runId);

        try {
            int failureCount = heartbeatService.countRecentFailures(tenantId);
            assertThat(failureCount).isGreaterThanOrEqualTo(3);

            Map<String, Object> report = heartbeatService.runHeartbeat(tenantId);
            assertThat(((Number) report.get("recent_failures")).intValue()).isGreaterThanOrEqualTo(3);
            // 3 failures > threshold of 2, so healthy must be false
            assertThat(report.get("healthy")).isEqualTo(false);
        } finally {
            jdbcTemplate.update("DELETE FROM ab_agent_run WHERE pid IN (?, ?, ?)", run1Pid, run2Pid, run3Pid);
        }
    }

    // ------------------------------------------------------------------ //
    //  Test 5: stale tasks detected when IN_PROGRESS task is old           //
    // ------------------------------------------------------------------ //

    @Test
    @Order(5)
    void runHeartbeat_reportsStaleTask_whenInProgressTaskIsOld() {
        Long tenantId = getTestTenant().getId();
        String taskPid = "hb-stale-task-" + runId;

        jdbcTemplate.update(
                "INSERT INTO ab_agent_task " +
                "(pid, tenant_id, title, task_status, assignee_type, deleted_flag, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'in_progress', 'agent', FALSE, " +
                "NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours')",
                taskPid, tenantId, "Stale task " + runId);

        try {
            int staleCount = heartbeatService.countStaleTasks(tenantId);
            assertThat(staleCount).isGreaterThan(0);

            Map<String, Object> report = heartbeatService.runHeartbeat(tenantId);
            assertThat(((Number) report.get("stale_tasks")).intValue()).isGreaterThan(0);
            assertThat(report.get("healthy")).isEqualTo(false);
        } finally {
            jdbcTemplate.update("DELETE FROM ab_agent_task WHERE pid = ?", taskPid);
        }
    }
}
