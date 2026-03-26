package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentEventDispatchService;
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
 * Integration tests for AgentEventDispatchService.
 *
 * <p>Uses NOT_SUPPORTED propagation so data committed by the service is visible
 * to subsequent query assertions. Each test cleans up its own data in a finally block.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentEventDispatchServiceTest extends BaseIntegrationTest {

    @Autowired
    private AgentEventDispatchService dispatchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String runId = String.valueOf(System.currentTimeMillis() % 100_000_000L);

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: matchesTrigger — matching eventType + modelCode
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void matchesTrigger_returnsTrueWhenEventTypeAndModelCodeMatch() {
        String triggersJson = """
                {"triggers":[{"eventType":"entity_status_changed","modelCode":"crm_lead"}]}
                """;

        boolean matched = dispatchService.matchesTrigger(
                triggersJson, "entity_status_changed", "crm_lead", Map.of());

        assertThat(matched).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: matchesTrigger — different eventType does not match
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void matchesTrigger_returnsFalseWhenEventTypeDiffers() {
        String triggersJson = """
                {"triggers":[{"eventType":"record_created","modelCode":"crm_lead"}]}
                """;

        boolean matched = dispatchService.matchesTrigger(
                triggersJson, "entity_status_changed", "crm_lead", Map.of());

        assertThat(matched).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: matchesTrigger — modelCode mismatch does not match
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void matchesTrigger_returnsFalseWhenModelCodeDiffers() {
        String triggersJson = """
                {"triggers":[{"eventType":"entity_status_changed","modelCode":"crm_opportunity"}]}
                """;

        boolean matched = dispatchService.matchesTrigger(
                triggersJson, "entity_status_changed", "crm_lead", Map.of());

        assertThat(matched).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4: matchesTrigger — condition satisfied
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void matchesTrigger_returnsTrueWhenConditionSatisfied() {
        String triggersJson = """
                {"triggers":[{"eventType":"entity_status_changed","modelCode":"crm_lead","condition":"newStatus=QUALIFIED"}]}
                """;

        boolean matched = dispatchService.matchesTrigger(
                triggersJson, "entity_status_changed", "crm_lead",
                Map.of("newStatus", "qualified"));

        assertThat(matched).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5: matchesTrigger — condition not satisfied
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void matchesTrigger_returnsFalseWhenConditionNotSatisfied() {
        String triggersJson = """
                {"triggers":[{"eventType":"entity_status_changed","modelCode":"crm_lead","condition":"newStatus=QUALIFIED"}]}
                """;

        boolean matched = dispatchService.matchesTrigger(
                triggersJson, "entity_status_changed", "crm_lead",
                Map.of("newStatus", "new"));

        assertThat(matched).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 6: findMatchingAgents — agent with matching trigger is returned
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void findMatchingAgents_returnsAgentWhenTriggerMatches() {
        Long tenantId = getTestTenant().getId();
        String agentCode = "test-agent-evt-" + runId;
        String agentPid = UniqueIdGenerator.generate();
        String triggersJson = """
                {"triggers":[{"eventType":"record_created","modelCode":"crm_complaint"}]}
                """;

        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition " +
                "(pid, tenant_id, agent_code, name, status, event_triggers, deleted_flag, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'active', ?::jsonb, FALSE, NOW(), NOW())",
                agentPid, tenantId, agentCode, "Test Event Agent " + runId, triggersJson);

        try {
            List<String> matched = dispatchService.findMatchingAgents(
                    tenantId, "record_created", "crm_complaint", Map.of());

            assertThat(matched).contains(agentCode);
        } finally {
            jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE pid = ?", agentPid);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 7: findMatchingAgents — agent with non-matching trigger is excluded
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    void findMatchingAgents_excludesAgentWhenTriggerDoesNotMatch() {
        Long tenantId = getTestTenant().getId();
        String agentCode = "test-agent-nomatch-" + runId;
        String agentPid = UniqueIdGenerator.generate();
        String triggersJson = """
                {"triggers":[{"eventType":"approval_timeout","modelCode":"some_other_model"}]}
                """;

        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition " +
                "(pid, tenant_id, agent_code, name, status, event_triggers, deleted_flag, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'active', ?::jsonb, FALSE, NOW(), NOW())",
                agentPid, tenantId, agentCode, "Non-matching Agent " + runId, triggersJson);

        try {
            List<String> matched = dispatchService.findMatchingAgents(
                    tenantId, "record_created", "crm_complaint", Map.of());

            assertThat(matched).doesNotContain(agentCode);
        } finally {
            jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE pid = ?", agentPid);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 8: cross-tenant isolation — agent in tenant A is not dispatched for tenant B event
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    void findMatchingAgents_crossTenantIsolation() {
        Long tenantA = getTestTenant().getId();
        long tenantB = tenantA + 99_999L; // a different non-existent tenant

        String agentCode = "test-agent-xten-" + runId;
        String agentPid = UniqueIdGenerator.generate();
        String triggersJson = """
                {"triggers":[{"eventType":"record_created"}]}
                """;

        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition " +
                "(pid, tenant_id, agent_code, name, status, event_triggers, deleted_flag, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'active', ?::jsonb, FALSE, NOW(), NOW())",
                agentPid, tenantA, agentCode, "Cross-Tenant Agent " + runId, triggersJson);

        try {
            // Query using tenantB — should NOT see tenantA's agent
            List<String> matchedForTenantB = dispatchService.findMatchingAgents(
                    tenantB, "record_created", null, Map.of());

            assertThat(matchedForTenantB).doesNotContain(agentCode);
        } finally {
            jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE pid = ?", agentPid);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 9: debounce — second dispatch within 30s is suppressed
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(9)
    void findMatchingAgents_debouncePreventsDuplicateDispatches() {
        Long tenantId = getTestTenant().getId();
        String agentCode = "test-agent-debounce-" + runId;
        String agentPid = UniqueIdGenerator.generate();
        String triggersJson = """
                {"triggers":[{"eventType":"rapid_event"}]}
                """;

        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition " +
                "(pid, tenant_id, agent_code, name, status, event_triggers, deleted_flag, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'active', ?::jsonb, FALSE, NOW(), NOW())",
                agentPid, tenantId, agentCode, "Debounce Test Agent " + runId, triggersJson);

        try {
            // First dispatch should succeed
            List<String> first = dispatchService.findMatchingAgents(
                    tenantId, "rapid_event", null, Map.of());
            assertThat(first).contains(agentCode);

            // Second dispatch immediately after — should be debounced (suppressed)
            List<String> second = dispatchService.findMatchingAgents(
                    tenantId, "rapid_event", null, Map.of());
            assertThat(second).doesNotContain(agentCode);
        } finally {
            jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE pid = ?", agentPid);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 10: dispatchMatchedAgents — creates task in ab_agent_task
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void dispatchMatchedAgents_createsTaskInDatabase() {
        Long tenantId = getTestTenant().getId();
        String agentCode = "test-agent-dispatch-" + runId;

        List<String> taskPids = dispatchService.dispatchMatchedAgents(
                tenantId, List.of(agentCode), "record_created",
                Map.of("recordId", "rec-123", "modelCode", "crm_complaint"));

        assertThat(taskPids).hasSize(1);
        String taskPid = taskPids.get(0);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT pid, assignee_id, task_status, tags FROM ab_agent_task WHERE pid = ?",
                    taskPid);

            assertThat(rows).hasSize(1);
            Map<String, Object> row = rows.get(0);
            assertThat(row.get("pid")).isEqualTo(taskPid);
            assertThat(row.get("assignee_id")).isEqualTo(agentCode);
            assertThat(row.get("task_status")).isEqualTo("backlog");
            assertThat(row.get("tags").toString()).contains("event_triggered");
        } finally {
            jdbcTemplate.update("DELETE FROM ab_agent_task WHERE pid = ?", taskPid);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 11: inactive agent is excluded from matching
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(11)
    void findMatchingAgents_excludesInactiveAgent() {
        Long tenantId = getTestTenant().getId();
        String agentCode = "test-agent-inactive-" + runId;
        String agentPid = UniqueIdGenerator.generate();
        String triggersJson = """
                {"triggers":[{"eventType":"record_created"}]}
                """;

        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition " +
                "(pid, tenant_id, agent_code, name, status, event_triggers, deleted_flag, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'inactive', ?::jsonb, FALSE, NOW(), NOW())",
                agentPid, tenantId, agentCode, "Inactive Agent " + runId, triggersJson);

        try {
            List<String> matched = dispatchService.findMatchingAgents(
                    tenantId, "record_created", null, Map.of());

            assertThat(matched).doesNotContain(agentCode);
        } finally {
            jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE pid = ?", agentPid);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 12: trigger without modelCode matches any modelCode
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(12)
    void findMatchingAgents_triggerWithoutModelCodeMatchesAnyModel() {
        Long tenantId = getTestTenant().getId();
        String agentCode = "test-agent-any-model-" + runId;
        String agentPid = UniqueIdGenerator.generate();
        // No modelCode in trigger — should match any model
        String triggersJson = """
                {"triggers":[{"eventType":"approval_timeout"}]}
                """;

        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition " +
                "(pid, tenant_id, agent_code, name, status, event_triggers, deleted_flag, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'active', ?::jsonb, FALSE, NOW(), NOW())",
                agentPid, tenantId, agentCode, "Any-Model Agent " + runId, triggersJson);

        try {
            List<String> matched = dispatchService.findMatchingAgents(
                    tenantId, "approval_timeout", "some_random_model", Map.of());

            assertThat(matched).contains(agentCode);
        } finally {
            jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE pid = ?", agentPid);
        }
    }
}
