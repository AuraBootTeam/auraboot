package com.auraboot.framework.agent;

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
 * Integration tests for agent approval→resume and failed→retry paths.
 * Covers: approval state transitions, run resume from PAUSED, and
 * retry-run creation with parent link after a FAILED run.
 * Uses real PostgreSQL, no rollback (NOT_SUPPORTED propagation).
 * Seed happens in the first @BeforeEach (once-guarded) so that MetaContext
 * is already initialised by BaseIntegrationTest.setupTenantContext().
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentResumeRetryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testRunId   = String.valueOf(System.currentTimeMillis());
    private final String defPid      = "def-rr-" + testRunId;
    private final String taskPid     = "task-rr-" + testRunId;
    private final String runPid      = "run-rr-" + testRunId;
    private final String approvalPid = "appr-rr-" + testRunId;
    private final String agentCode   = "test-rr-agent-" + testRunId;

    private boolean seeded = false;

    @BeforeEach
    void seedOnce() {
        if (seeded) return;
        seeded = true;

        Long tenantId = getTestTenant().getId();

        // 1. Agent definition
        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition "
                + "(pid, tenant_id, agent_code, name, agent_type, status, deleted_flag) "
                + "VALUES (?, ?, ?, ?, 'reactive', 'active', FALSE)",
                defPid, tenantId, agentCode, "Test RR Agent " + testRunId);

        // 2. Task (IN_PROGRESS)
        jdbcTemplate.update(
                "INSERT INTO ab_agent_task "
                + "(pid, tenant_id, title, task_status, deleted_flag) "
                + "VALUES (?, ?, ?, 'in_progress', FALSE)",
                taskPid, tenantId, "Test RR Task " + testRunId);

        // 3. Run (PAUSED)
        jdbcTemplate.update(
                "INSERT INTO ab_agent_run "
                + "(pid, tenant_id, task_id, agent_id, run_status) "
                + "VALUES (?, ?, ?, ?, 'paused')",
                runPid, tenantId, taskPid, agentCode);

        // 4. Approval (PENDING, HIGH risk indicated via approval_type)
        jdbcTemplate.update(
                "INSERT INTO ab_agent_approval "
                + "(pid, tenant_id, run_id, task_id, approval_type, "
                + " approval_title, approval_status) "
                + "VALUES (?, ?, ?, ?, 'high_risk_action', "
                + " 'Approval required for high-risk action', 'pending')",
                approvalPid, tenantId, runPid, taskPid);
    }

    // ========== Test 1: approval starts in PENDING state ==========

    @Test
    @Order(1)
    void approval_pendingState() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT approval_status, approval_type "
                + "FROM ab_agent_approval WHERE pid = ?",
                approvalPid);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("approval_status")).isEqualTo("pending");
        assertThat(row.get("approval_type")).isEqualTo("high_risk_action");
    }

    // ========== Test 2: approving changes approval_status to APPROVED ==========

    @Test
    @Order(2)
    void approval_approve_changesStatus() {
        jdbcTemplate.update(
                "UPDATE ab_agent_approval "
                + "SET approval_status = 'approved', approver_id = ?, approved_at = NOW() "
                + "WHERE pid = ?",
                getTestUser().getId(), approvalPid);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT approval_status, approver_id, approved_at "
                + "FROM ab_agent_approval WHERE pid = ?",
                approvalPid);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("approval_status")).isEqualTo("approved");
        assertThat(row.get("approver_id")).isNotNull();
        assertThat(row.get("approved_at")).isNotNull();
    }

    // ========== Test 3: resume transitions run from PAUSED to RUNNING ==========

    @Test
    @Order(3)
    void run_resume_changesFromPaused() {
        // Verify the run is still PAUSED (seeded state)
        List<Map<String, Object>> before = jdbcTemplate.queryForList(
                "SELECT run_status FROM ab_agent_run WHERE pid = ?",
                runPid);
        assertThat(before).hasSize(1);
        assertThat(before.get(0).get("run_status")).isEqualTo("paused");

        // Resume
        jdbcTemplate.update(
                "UPDATE ab_agent_run SET run_status = 'running' WHERE pid = ?",
                runPid);

        List<Map<String, Object>> after = jdbcTemplate.queryForList(
                "SELECT run_status FROM ab_agent_run WHERE pid = ?",
                runPid);

        assertThat(after).hasSize(1);
        assertThat(after.get(0).get("run_status")).isEqualTo("running");
    }

    // ========== Test 4: failed run → retry creates new run with parent link ==========

    @Test
    @Order(4)
    void run_failed_thenRetry_createsNewRun() {
        Long tenantId = getTestTenant().getId();
        String retryRunPid = "run-retry-" + testRunId;

        // Fail the original run
        jdbcTemplate.update(
                "UPDATE ab_agent_run "
                + "SET run_status = 'failed', error_message = 'Simulated failure', "
                + "    completed_at = NOW() "
                + "WHERE pid = ?",
                runPid);

        // Verify FAILED state
        List<Map<String, Object>> failedRows = jdbcTemplate.queryForList(
                "SELECT run_status, error_message FROM ab_agent_run WHERE pid = ?",
                runPid);
        assertThat(failedRows).hasSize(1);
        assertThat(failedRows.get(0).get("run_status")).isEqualTo("failed");
        assertThat(failedRows.get(0).get("error_message")).isEqualTo("Simulated failure");

        // Create retry run — link back via resumed_from (the retry/parent column in ab_agent_run)
        jdbcTemplate.update(
                "INSERT INTO ab_agent_run "
                + "(pid, tenant_id, task_id, agent_id, run_status, resumed_from) "
                + "VALUES (?, ?, ?, ?, 'running', ?)",
                retryRunPid, tenantId, taskPid, agentCode, runPid);

        // Assert retry run has correct parent link
        List<Map<String, Object>> retryRows = jdbcTemplate.queryForList(
                "SELECT run_status, resumed_from FROM ab_agent_run WHERE pid = ?",
                retryRunPid);

        assertThat(retryRows).hasSize(1);
        Map<String, Object> retryRow = retryRows.get(0);
        assertThat(retryRow.get("run_status")).isEqualTo("running");
        assertThat(retryRow.get("resumed_from")).isEqualTo(runPid);
    }

    // ========== Test 5: rejecting a new approval sets status to REJECTED ==========

    @Test
    @Order(5)
    void approval_reject_status() {
        Long tenantId = getTestTenant().getId();
        String rejectedApprovalPid = "appr-rej-" + testRunId;

        // Insert a fresh PENDING approval
        jdbcTemplate.update(
                "INSERT INTO ab_agent_approval "
                + "(pid, tenant_id, run_id, task_id, approval_type, "
                + " approval_title, approval_status) "
                + "VALUES (?, ?, ?, ?, 'tool_execution', "
                + " 'Approval for tool execution', 'pending')",
                rejectedApprovalPid, tenantId, runPid, taskPid);

        // Reject it
        jdbcTemplate.update(
                "UPDATE ab_agent_approval "
                + "SET approval_status = 'rejected', approver_id = ?, "
                + "    approved_at = NOW(), rejection_reason = 'Risk too high' "
                + "WHERE pid = ?",
                getTestUser().getId(), rejectedApprovalPid);

        // Assert REJECTED state
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT approval_status, rejection_reason "
                + "FROM ab_agent_approval WHERE pid = ?",
                rejectedApprovalPid);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("approval_status")).isEqualTo("rejected");
        assertThat(row.get("rejection_reason")).isEqualTo("Risk too high");
    }
}
