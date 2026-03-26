package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentCostReportService;
import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.service.AgentPromptAssemblyService;
import com.auraboot.framework.agent.service.AgentSelfImprovementService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for Phase 6 innovation features: G1, G2, G3.
 *
 * <ul>
 *   <li>G1 — Agent self-improvement: LESSON extraction from failed runs</li>
 *   <li>G2 — Cross-agent shareable memories</li>
 *   <li>G3 — Cost aggregation reporting</li>
 * </ul>
 *
 * Uses real PostgreSQL (no mocking). Data is NOT rolled back so it remains
 * visible for post-test inspection (per project convention for non-rollback tests).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentInnovationFeaturesTest extends BaseIntegrationTest {

    @Autowired
    private AgentSelfImprovementService selfImprovementService;

    @Autowired
    private AgentCostReportService costReportService;

    @Autowired
    private AgentMemoryService memoryService;

    @Autowired
    private AgentPromptAssemblyService promptAssemblyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Use a short suffix (last 6 digits of epoch millis) so all pid values fit in VARCHAR(26)
    private final String runId = String.valueOf(System.currentTimeMillis()).substring(7);
    private final String agentCode = "g1g2g3-agent-" + runId;
    private final String otherAgentCode = "g2-other-agent-" + runId;

    // =========================================================================
    // Setup: seed a FAILED run so G1 tests have data
    // =========================================================================

    private String failedRunPid;

    @BeforeAll
    void seedFailedRun() {
        // setupTenantContext() from BaseIntegrationTest is @BeforeEach, not yet called here,
        // so we call it once to get a stable tenant context.
        // Actually BaseIntegrationTest uses @BeforeEach — the first test call initialises context.
        // We seed run data directly via JDBC after context is available.
    }

    // =========================================================================
    // G1 — Self-Improvement
    // =========================================================================

    @Test
    @Order(1)
    void g1_extractLessons_createsLessonMemory_forFailedRun() {
        Long tenantId = getTestTenant().getId();

        // Insert a synthetic FAILED run
        String runPid = "test-run-" + runId;
        failedRunPid = runPid;
        jdbcTemplate.update(
                "INSERT INTO ab_agent_run "
                + "(pid, tenant_id, task_id, agent_id, run_status, error_message, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'failed', ?, NOW(), NOW())",
                runPid, tenantId, "task-" + runId, agentCode,
                "ToolExecutionException: tool 'crm_create_contact' failed — required field 'email' is missing");

        int count = selfImprovementService.extractLessonsFromFailedRun(tenantId, agentCode, runPid);

        assertThat(count).isEqualTo(1);

        // Verify the LESSON memory exists in the database
        List<Map<String, Object>> lessons = jdbcTemplate.queryForList(
                "SELECT memory_type, category, memory_title, memory_content, importance, source_run_id "
                + "FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? AND memory_type = 'lesson' "
                + "AND source_run_id = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, agentCode, runPid);

        assertThat(lessons).hasSize(1);
        Map<String, Object> lesson = lessons.get(0);
        assertThat(lesson.get("category")).isEqualTo("agent");
        assertThat((String) lesson.get("memory_title")).contains("Failure lesson");
        assertThat((String) lesson.get("memory_content")).contains("ToolExecutionException");
        assertThat(((Number) lesson.get("importance")).intValue()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @Order(2)
    void g1_extractLessons_returnsZero_forSuccessfulRun() {
        Long tenantId = getTestTenant().getId();

        String successRunPid = "test-run-success-" + runId;
        jdbcTemplate.update(
                "INSERT INTO ab_agent_run "
                + "(pid, tenant_id, task_id, agent_id, run_status, error_message, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'success', NULL, NOW(), NOW())",
                successRunPid, tenantId, "task-success-" + runId, agentCode);

        int count = selfImprovementService.extractLessonsFromFailedRun(tenantId, agentCode, successRunPid);

        assertThat(count).isEqualTo(0);
    }

    @Test
    @Order(3)
    void g1_extractLessons_returnsZero_whenErrorMessageTooShort() {
        Long tenantId = getTestTenant().getId();

        String shortErrRunPid = "test-run-short-err-" + runId;
        jdbcTemplate.update(
                "INSERT INTO ab_agent_run "
                + "(pid, tenant_id, task_id, agent_id, run_status, error_message, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'failed', 'err', NOW(), NOW())",
                shortErrRunPid, tenantId, "task-shorterr-" + runId, agentCode);

        int count = selfImprovementService.extractLessonsFromFailedRun(tenantId, agentCode, shortErrRunPid);

        assertThat(count).isEqualTo(0);
    }

    @Test
    @Order(4)
    void g1_getImprovementStats_returnsCorrectCounts() {
        Long tenantId = getTestTenant().getId();

        // Run the lesson extraction first to ensure LESSON memory exists (from test 1)
        Map<String, Object> stats = selfImprovementService.getImprovementStats(tenantId, agentCode);

        assertThat(stats).containsKey("totalRuns");
        assertThat(stats).containsKey("successfulRuns");
        assertThat(stats).containsKey("failedRuns");
        assertThat(stats).containsKey("successRate");
        assertThat(stats).containsKey("lessonCount");
        assertThat(stats.get("agentCode")).isEqualTo(agentCode);

        long totalRuns = ((Number) stats.get("totalRuns")).longValue();
        long failedRuns = ((Number) stats.get("failedRuns")).longValue();
        long lessonCount = ((Number) stats.get("lessonCount")).longValue();

        // We inserted 3 runs (1 FAILED with error, 1 SUCCESS, 1 FAILED short error)
        assertThat(totalRuns).isGreaterThanOrEqualTo(2);
        assertThat(failedRuns).isGreaterThanOrEqualTo(1);
        // At least 1 lesson from test @Order(1)
        assertThat(lessonCount).isGreaterThanOrEqualTo(1);

        double successRate = ((Number) stats.get("successRate")).doubleValue();
        assertThat(successRate).isBetween(0.0, 1.0);
    }

    // =========================================================================
    // G2 — Cross-agent shared memories
    // =========================================================================

    @Test
    @Order(5)
    void g2_sharedMemory_visibleAcrossAgents() {
        Long tenantId = getTestTenant().getId();

        // Insert a shareable memory for agentCode
        String memPid = "shared-mem-" + runId;
        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory "
                + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                + " memory_title, memory_content, importance, shareable, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, 'fact', 'agent', 'Shared Best Practice', "
                + "'Always validate email format before CRM insert', 8, TRUE, NOW(), NOW(), FALSE)",
                memPid, tenantId, agentCode);

        // loadSharedMemories should include it regardless of the requesting agent
        String sharedContent = promptAssemblyService.loadSharedMemories(tenantId, 5000);

        assertThat(sharedContent).isNotNull();
        assertThat(sharedContent).contains("Always validate email format before CRM insert");
        assertThat(sharedContent).contains(agentCode);
    }

    @Test
    @Order(6)
    void g2_nonShareableMemory_notVisibleToOtherAgents() {
        Long tenantId = getTestTenant().getId();

        // Insert a private (non-shareable) memory for otherAgentCode
        String privatePid = "private-mem-" + runId;
        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory "
                + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                + " memory_title, memory_content, importance, shareable, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, 'fact', 'agent', 'Private Note', 'SECRET internal data', 7, FALSE, NOW(), NOW(), FALSE)",
                privatePid, tenantId, otherAgentCode);

        String sharedContent = promptAssemblyService.loadSharedMemories(tenantId, 5000);

        // Private memory must NOT appear in shared pool
        if (sharedContent != null) {
            assertThat(sharedContent).doesNotContain("SECRET internal data");
        }
    }

    @Test
    @Order(7)
    void g2_sharedMemory_includesLessonFromOtherAgent() {
        Long tenantId = getTestTenant().getId();

        // Insert a shareable LESSON from otherAgentCode
        String lessonPid = "shared-lesson-" + runId;
        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory "
                + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                + " memory_title, memory_content, importance, shareable, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, 'lesson', 'agent', 'Shared Lesson from other agent', "
                + "'Do not call list API without pagination params', 9, TRUE, NOW(), NOW(), FALSE)",
                lessonPid, tenantId, otherAgentCode);

        String sharedContent = promptAssemblyService.loadSharedMemories(tenantId, 8000);

        assertThat(sharedContent).isNotNull();
        assertThat(sharedContent).contains("Do not call list API without pagination params");
        assertThat(sharedContent).contains(otherAgentCode);
    }

    // =========================================================================
    // G3 — Cost Reporting
    // =========================================================================

    @Test
    @Order(8)
    void g3_getCostByAgent_returnsCostData() {
        Long tenantId = getTestTenant().getId();

        // Insert a synthetic run with cost for g3-specific agent
        String g3AgentCode = "g3-cost-agent-" + runId;
        jdbcTemplate.update(
                "INSERT INTO ab_agent_run "
                + "(pid, tenant_id, task_id, agent_id, run_status, total_cost, "
                + " input_tokens, output_tokens, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'success', 0.05, 1000, 500, NOW(), NOW())",
                "g3-run-1-" + runId, tenantId, "g3-task-1-" + runId, g3AgentCode);

        jdbcTemplate.update(
                "INSERT INTO ab_agent_run "
                + "(pid, tenant_id, task_id, agent_id, run_status, total_cost, "
                + " input_tokens, output_tokens, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'failed', 0.02, 400, 100, NOW(), NOW())",
                "g3-run-2-" + runId, tenantId, "g3-task-2-" + runId, g3AgentCode);

        List<Map<String, Object>> rows = costReportService.getCostByAgent(tenantId);

        assertThat(rows).isNotEmpty();

        // Find our test agent
        Map<String, Object> agentRow = rows.stream()
                .filter(r -> g3AgentCode.equals(r.get("agent_id")))
                .findFirst()
                .orElse(null);

        assertThat(agentRow).isNotNull();
        assertThat(((Number) agentRow.get("total_runs")).longValue()).isEqualTo(2);
        assertThat(((Number) agentRow.get("successful_runs")).longValue()).isEqualTo(1);
        assertThat(((Number) agentRow.get("failed_runs")).longValue()).isEqualTo(1);

        double totalCost = ((Number) agentRow.get("total_cost")).doubleValue();
        assertThat(totalCost).isGreaterThan(0.0);

        long totalInputTokens = ((Number) agentRow.get("total_input_tokens")).longValue();
        assertThat(totalInputTokens).isEqualTo(1400);
    }

    @Test
    @Order(9)
    void g3_getCostByDay_returnsDailyBreakdown() {
        Long tenantId = getTestTenant().getId();

        // Insert a run with cost today for g3-daily agent
        String g3DailyAgent = "g3-daily-agent-" + runId;
        jdbcTemplate.update(
                "INSERT INTO ab_agent_run "
                + "(pid, tenant_id, task_id, agent_id, run_status, total_cost, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'success', 0.10, NOW(), NOW())",
                "g3-daily-run-" + runId, tenantId, "g3-daily-task-" + runId, g3DailyAgent);

        List<Map<String, Object>> rows = costReportService.getCostByDay(tenantId, 7);

        assertThat(rows).isNotEmpty();

        // Today's row must exist
        Map<String, Object> todayRow = rows.get(0); // newest first
        assertThat(todayRow.get("run_date")).isNotNull();
        assertThat(((Number) todayRow.get("run_count")).longValue()).isGreaterThan(0);
    }

    @Test
    @Order(10)
    void g3_getCostByDay_rejectsInvalidDaysRange() {
        Long tenantId = getTestTenant().getId();

        assertThatThrownBy(() -> costReportService.getCostByDay(tenantId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days must be between");

        assertThatThrownBy(() -> costReportService.getCostByDay(tenantId, 400))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days must be between");
    }

    @Test
    @Order(11)
    void g3_getTenantCostSummary_returnsAggregatedData() {
        Long tenantId = getTestTenant().getId();

        Map<String, Object> summary = costReportService.getTenantCostSummary(tenantId);

        assertThat(summary).isNotEmpty();
        assertThat(summary).containsKeys(
                "total_cost", "total_runs", "successful_runs", "failed_runs",
                "total_input_tokens", "total_output_tokens", "distinct_agents");

        long totalRuns = ((Number) summary.get("total_runs")).longValue();
        assertThat(totalRuns).isGreaterThan(0);  // We inserted runs in earlier tests

        long distinctAgents = ((Number) summary.get("distinct_agents")).longValue();
        assertThat(distinctAgents).isGreaterThan(0);
    }
}
