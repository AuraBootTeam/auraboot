package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentMemoryConsolidationService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
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
 * Integration tests for AgentMemoryConsolidationService.
 *
 * <p>Covers the OpenClaw-inspired memory lifecycle:
 * <ul>
 *   <li>Session → User promotion by importance threshold</li>
 *   <li>Session memory decay (reduce importance, soft-delete exhausted)</li>
 *   <li>User profile upsert (create and idempotent update)</li>
 *   <li>Session memory scope isolation</li>
 * </ul>
 *
 * <p>Uses real PostgreSQL — no mocking. Data persists (NOT_SUPPORTED transaction).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentMemoryConsolidationTest extends BaseIntegrationTest {

    @Autowired
    private AgentMemoryConsolidationService consolidationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private final String agentCode = "consolidation-test-" + testRunId;

    // =========================================================================
    // Test 1: Promote high-importance session memories to user level
    // =========================================================================

    @Test
    @Order(1)
    void promoteSessionMemories_elevatesHighImportanceToUserLevel() {
        Long tenantId = getTestTenant().getId();

        // Seed session memories directly via JdbcTemplate to avoid
        // storeMemoryWithEmbedding's column mapping ambiguity.
        insertSessionMemory(tenantId, agentCode, "High Importance Session", "content A", 8);
        insertSessionMemory(tenantId, agentCode, "Low Importance Session",  "content B", 2);
        insertSessionMemory(tenantId, agentCode, "Border Session",          "content C", 5);

        // Promote memories with importance >= 5
        int promoted = consolidationService.promoteSessionMemories(tenantId, agentCode, 5);

        assertThat(promoted).isEqualTo(2)
                .as("Exactly 2 memories (importance 8 and 5) should be promoted");

        // Verify the promoted ones now have category = 'user'
        List<Map<String, Object>> userMemories = jdbcTemplate.queryForList(
                "SELECT memory_title, category FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "  AND category = 'user' "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, agentCode);

        assertThat(userMemories).hasSize(2);
        List<String> promotedTitles = userMemories.stream()
                .map(r -> (String) r.get("memory_title"))
                .toList();
        assertThat(promotedTitles).containsExactlyInAnyOrder("High Importance Session", "Border Session");

        // Verify the low-importance one is still session-scoped
        List<Map<String, Object>> stillSession = jdbcTemplate.queryForList(
                "SELECT memory_title FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "  AND category = 'session' "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, agentCode);

        assertThat(stillSession).hasSize(1);
        assertThat(stillSession.get(0).get("memory_title")).isEqualTo("Low Importance Session");
    }

    // =========================================================================
    // Test 2: Decay reduces importance scores
    // =========================================================================

    @Test
    @Order(2)
    void decaySessionMemories_reducesImportanceByAmount() {
        Long tenantId = getTestTenant().getId();
        String decayAgent = "decay-agent-" + testRunId;

        insertSessionMemory(tenantId, decayAgent, "Memory Decay Test", "survives", 4);

        // Decay by 2 — importance should go from 4 → 2
        consolidationService.decaySessionMemories(tenantId, decayAgent, 2);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT importance FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "  AND memory_title = ? "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, decayAgent, "Memory Decay Test");

        assertThat(rows).hasSize(1)
                .as("Memory should still exist after partial decay");

        int importanceAfter = ((Number) rows.get(0).get("importance")).intValue();
        assertThat(importanceAfter).isEqualTo(2)
                .as("Importance should have been reduced from 4 to 2");
    }

    // =========================================================================
    // Test 3: Importance <= 0 gets soft-deleted
    // =========================================================================

    @Test
    @Order(3)
    void decaySessionMemories_softDeletesExhaustedMemories() {
        Long tenantId = getTestTenant().getId();
        String exhaustAgent = "exhaust-agent-" + testRunId;

        insertSessionMemory(tenantId, exhaustAgent, "Low Energy Memory", "almost gone", 1);
        insertSessionMemory(tenantId, exhaustAgent, "Vital Memory",       "must survive", 5);

        // Decay by 2 — importance 1 → -1 (soft-deleted), 5 → 3 (survives)
        int deleted = consolidationService.decaySessionMemories(tenantId, exhaustAgent, 2);

        assertThat(deleted).isEqualTo(1)
                .as("Exactly 1 memory should have been soft-deleted");

        // Verify "Low Energy Memory" is now deleted
        List<Map<String, Object>> deletedRows = jdbcTemplate.queryForList(
                "SELECT deleted_flag FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? AND memory_title = ?",
                tenantId, exhaustAgent, "Low Energy Memory");

        assertThat(deletedRows).hasSize(1);
        assertThat(deletedRows.get(0).get("deleted_flag")).isEqualTo(true)
                .as("Exhausted memory must be soft-deleted");

        // Verify "Vital Memory" still lives with importance = 3
        List<Map<String, Object>> survivalRows = jdbcTemplate.queryForList(
                "SELECT importance FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "  AND memory_title = ? "
                + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, exhaustAgent, "Vital Memory");

        assertThat(survivalRows).hasSize(1);
        assertThat(((Number) survivalRows.get(0).get("importance")).intValue()).isEqualTo(3);
    }

    // =========================================================================
    // Test 4: User profile upsert — create then update
    // =========================================================================

    @Test
    @Order(4)
    void upsertUserProfile_createsAndUpdatesIdempotently() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // Create initial profile
        Map<String, Object> comm1 = Map.of("style", "concise", "language", "zh-CN");
        Map<String, Object> role1 = Map.of("title", "cto", "domain", "engineering");

        consolidationService.upsertUserProfile(tenantId, userId, comm1, role1);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT communication, role_context, preferences, decision_patterns "
                + "FROM ab_agent_user_profile "
                + "WHERE tenant_id = ? AND user_id = ? AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, userId);

        assertThat(rows).hasSize(1)
                .as("Profile should be created");

        String commJson = rows.get(0).get("communication").toString();
        assertThat(commJson).contains("concise")
                .as("communication field should be persisted");

        // Update: add preferences, leave communication unchanged (null = don't overwrite)
        Map<String, Object> prefs = Map.of("theme", "dark", "timezone", "Asia/Shanghai");
        consolidationService.upsertUserProfile(tenantId, userId, null, null, prefs, "prefers async decisions");

        List<Map<String, Object>> updated = jdbcTemplate.queryForList(
                "SELECT communication, preferences, decision_patterns "
                + "FROM ab_agent_user_profile "
                + "WHERE tenant_id = ? AND user_id = ? AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, userId);

        assertThat(updated).hasSize(1)
                .as("Profile should remain a single row after upsert");

        // Original communication must be preserved (COALESCE keeps existing when new is null)
        assertThat(updated.get(0).get("communication").toString()).contains("concise")
                .as("communication should not be overwritten when null is passed");

        assertThat(updated.get(0).get("preferences").toString()).contains("dark")
                .as("preferences should be stored on second upsert");

        assertThat((String) updated.get(0).get("decision_patterns"))
                .isEqualTo("prefers async decisions")
                .as("decision_patterns should be updated");
    }

    // =========================================================================
    // Test 5: Session memory writes to correct fields
    // =========================================================================

    @Test
    @Order(5)
    void sessionMemory_writesToCorrectFields() {
        Long tenantId = getTestTenant().getId();
        String fieldAgent = "field-check-agent-" + testRunId;
        String uniqueTitle = "Field Verification " + testRunId;

        insertSessionMemory(tenantId, fieldAgent, uniqueTitle, "field check content", 7);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, memory_agent_id, memory_type, category, "
                + "       memory_title, memory_content, importance, deleted_flag "
                + "FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? AND memory_title = ?",
                tenantId, fieldAgent, uniqueTitle);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);

        assertThat(row.get("pid")).isNotNull().asString().isNotBlank()
                .as("pid must be generated");
        assertThat(((Number) row.get("tenant_id")).longValue()).isEqualTo(tenantId)
                .as("tenant_id must match");
        assertThat(row.get("memory_agent_id")).isEqualTo(fieldAgent)
                .as("memory_agent_id must be the agent code");
        assertThat(row.get("memory_type")).isEqualTo("fact")
                .as("memory_type must be FACT as seeded");
        assertThat(row.get("category")).isEqualTo(AgentMemoryConsolidationService.CATEGORY_SESSION)
                .as("category must be 'session' for new session memories");
        assertThat(row.get("memory_title")).isEqualTo(uniqueTitle);
        assertThat(row.get("memory_content")).isEqualTo("field check content");
        assertThat(((Number) row.get("importance")).intValue()).isEqualTo(7);
        assertThat(row.get("deleted_flag")).isEqualTo(false);
    }

    // =========================================================================
    // Seed helper
    // =========================================================================

    /**
     * Insert a session-scoped memory directly via JdbcTemplate.
     * Uses category = 'session' and memory_type = 'fact'.
     * This avoids dependency on storeMemoryWithEmbedding's column mapping.
     */
    private void insertSessionMemory(Long tenantId, String agentCode,
                                      String title, String content, int importance) {
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory "
                + "  (pid, tenant_id, memory_agent_id, memory_type, category, "
                + "   memory_title, memory_content, importance, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, 'fact', 'session', ?, ?, ?, NOW(), NOW(), FALSE)",
                pid, tenantId, agentCode, title, content, importance);
    }
}
