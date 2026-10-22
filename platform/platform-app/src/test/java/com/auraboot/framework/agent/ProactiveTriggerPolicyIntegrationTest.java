package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.ProactiveTriggerPolicyService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@DisplayName("Governed proactive schedule/event policy")
class ProactiveTriggerPolicyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProactiveTriggerPolicyService policy;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("event uses an enrolled proactive employee and atomically exhausts its daily budget")
    void eventBudgetIsClaimedExactlyOnce() {
        long tenantId = getTestTenant().getId();
        String code = seedProactiveAgent(
                tenantId,
                """
                {"allowedChannels":["event"],"timezone":"UTC","dailyRunBudget":1}
                """);

        ProactiveTriggerPolicyService.Decision first =
                policy.evaluateAndClaimEvent(
                        tenantId, code, Instant.parse("2026-07-29T12:00:00Z"));
        ProactiveTriggerPolicyService.Decision second =
                policy.evaluateAndClaimEvent(
                        tenantId, code, Instant.parse("2026-07-29T12:01:00Z"));

        assertThat(first.allowed()).isTrue();
        assertThat(first.agentCode()).isEqualTo(code);
        assertThat(second.allowed()).isFalse();
        assertThat(second.reason()).isEqualTo("DAILY_BUDGET_EXHAUSTED");
        assertThat(jdbc.queryForObject(
                "SELECT run_count FROM ab_agent_proactive_usage "
                        + "WHERE tenant_id = ? AND agent_code = ? "
                        + "AND usage_date = DATE '2026-07-29'",
                Integer.class,
                tenantId,
                code)).isEqualTo(1);
    }

    @Test
    @DisplayName("overnight quiet hours and invalid manager scope block before budget is consumed")
    void scheduleQuietHoursAndManagerScopeFailClosed() {
        long tenantId = getTestTenant().getId();
        String code = seedProactiveAgent(
                tenantId,
                """
                {"allowedChannels":["schedule"],"timezone":"Asia/Shanghai"}
                """);

        Map<String, Object> quietSchedule = Map.of(
                "agent_code", code,
                "timezone", "Asia/Shanghai",
                "quiet_hours_start", "22:00",
                "quiet_hours_end", "07:00",
                "daily_run_budget", 5,
                "concurrency_limit", 1);
        ProactiveTriggerPolicyService.Decision quiet =
                policy.evaluateAndClaimSchedule(
                        tenantId,
                        quietSchedule,
                        Instant.parse("2026-07-29T16:30:00Z"));
        assertThat(quiet.allowed()).isFalse();
        assertThat(quiet.reason()).isEqualTo("QUIET_HOURS");

        Map<String, Object> invalidManager = new java.util.HashMap<>(quietSchedule);
        invalidManager.remove("quiet_hours_start");
        invalidManager.remove("quiet_hours_end");
        invalidManager.put("manager_member_id", Long.MAX_VALUE);
        ProactiveTriggerPolicyService.Decision manager =
                policy.evaluateAndClaimSchedule(
                        tenantId,
                        invalidManager,
                        Instant.parse("2026-07-29T08:00:00Z"));
        assertThat(manager.allowed()).isFalse();
        assertThat(manager.reason()).isEqualTo("MANAGER_SCOPE_INVALID");

        Integer usageRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_proactive_usage "
                        + "WHERE tenant_id = ? AND agent_code = ?",
                Integer.class,
                tenantId,
                code);
        assertThat(usageRows).isZero();
    }

    @Test
    @DisplayName("quiet-hours calculation supports ranges that cross midnight")
    void quietHoursCrossMidnight() {
        assertThat(ProactiveTriggerPolicyService.insideQuietHours(
                LocalTime.of(23, 0),
                LocalTime.of(22, 0),
                LocalTime.of(7, 0))).isTrue();
        assertThat(ProactiveTriggerPolicyService.insideQuietHours(
                LocalTime.of(6, 59),
                LocalTime.of(22, 0),
                LocalTime.of(7, 0))).isTrue();
        assertThat(ProactiveTriggerPolicyService.insideQuietHours(
                LocalTime.of(12, 0),
                LocalTime.of(22, 0),
                LocalTime.of(7, 0))).isFalse();
    }

    private String seedProactiveAgent(long tenantId, String proactivePolicy) {
        String code = "proactive_" + UniqueIdGenerator.generate().toLowerCase();
        jdbc.update(
                """
                INSERT INTO ab_agent_definition
                    (pid, tenant_id, agent_code, name, agent_type, status,
                     employee_id, system_user_id, proactive_policy,
                     deleted_flag, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'proactive', 'active', ?, ?, ?::jsonb,
                        FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UniqueIdGenerator.generate(),
                tenantId,
                code,
                "Proactive policy fixture",
                700_001L,
                700_002L,
                proactivePolicy);
        return code;
    }
}
