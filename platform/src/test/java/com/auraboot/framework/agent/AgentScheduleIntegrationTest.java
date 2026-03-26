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
 * Integration tests for agent schedule lifecycle.
 * Covers: schedule creation, run-count increment, and max-runs expiry.
 * Uses real PostgreSQL, no rollback (NOT_SUPPORTED propagation).
 * Seed happens in the first @BeforeEach (once-guarded) so that MetaContext
 * is already initialised by BaseIntegrationTest.setupTenantContext().
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentScheduleIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private final String defPid    = "def-sched-" + testRunId;
    private final String schedPid  = "sched-" + testRunId;
    private final String agentCode = "test-sched-agent-" + testRunId;

    private boolean seeded = false;

    @BeforeEach
    void seedOnce() {
        if (seeded) return;
        seeded = true;

        Long tenantId = getTestTenant().getId();

        // Seed 1 agent definition
        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition "
                + "(pid, tenant_id, agent_code, name, agent_type, status, deleted_flag) "
                + "VALUES (?, ?, ?, ?, 'reactive', 'active', FALSE)",
                defPid, tenantId, agentCode, "Test Schedule Agent " + testRunId);

        // Seed 1 schedule (CRON type, schedule_status=ACTIVE, max_runs=3, run_count=0)
        jdbcTemplate.update(
                "INSERT INTO ab_agent_schedule "
                + "(pid, tenant_id, title, schedule_type, cron_expression, "
                + " schedule_status, max_runs, run_count, deleted_flag) "
                + "VALUES (?, ?, ?, 'cron', '*/1 * * * *', 'active', 3, 0, FALSE)",
                schedPid, tenantId, "Test Schedule " + testRunId);
    }

    // ========== Test 1: schedule exists and is ACTIVE with run_count=0 ==========

    @Test
    @Order(1)
    void schedule_existsAndActive() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT schedule_status, run_count, max_runs "
                + "FROM ab_agent_schedule WHERE pid = ?",
                schedPid);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("schedule_status")).isEqualTo("active");
        assertThat(((Number) row.get("run_count")).intValue()).isEqualTo(0);
        assertThat(((Number) row.get("max_runs")).intValue()).isEqualTo(3);
    }

    // ========== Test 2: triggering a run increments run_count and sets last_run_at ==========

    @Test
    @Order(2)
    void schedule_trigger_incrementsRunCount() {
        jdbcTemplate.update(
                "UPDATE ab_agent_schedule "
                + "SET run_count = run_count + 1, last_run_at = NOW() "
                + "WHERE pid = ?",
                schedPid);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT run_count, last_run_at FROM ab_agent_schedule WHERE pid = ?",
                schedPid);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("run_count")).intValue()).isEqualTo(1);
        assertThat(row.get("last_run_at")).isNotNull();
    }

    // ========== Test 3: when run_count reaches max_runs the schedule is EXPIRED ==========

    @Test
    @Order(3)
    void schedule_maxRunsReached_expires() {
        jdbcTemplate.update(
                "UPDATE ab_agent_schedule "
                + "SET run_count = max_runs, schedule_status = 'expired' "
                + "WHERE pid = ?",
                schedPid);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT schedule_status, run_count, max_runs "
                + "FROM ab_agent_schedule WHERE pid = ?",
                schedPid);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("schedule_status")).isEqualTo("expired");

        int runCount = ((Number) row.get("run_count")).intValue();
        int maxRuns  = ((Number) row.get("max_runs")).intValue();
        assertThat(runCount).isEqualTo(maxRuns);
    }
}
