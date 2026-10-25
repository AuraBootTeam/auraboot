package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AgentMemoryService.
 * Covers memory storage, access tracking, importance-ordered retrieval,
 * semantic keyword search, deduplication, and agent-scoped isolation.
 * Uses real database, no mocking. Data persists (no rollback).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentMemoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentMemoryService agentMemoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private final String agentCode = "test-agent-" + testRunId;

    // ========== Test 1: storeMemoryWithEmbedding creates records ==========

    @Test
    @Order(1)
    void storeMemory_createsRecord() {
        Long tenantId = getTestTenant().getId();

        agentMemoryService.storeMemoryWithEmbedding(
                tenantId, agentCode, "fact",
                "System Architecture",
                "The system uses microservices",
                5, "run-" + testRunId, null);

        agentMemoryService.storeMemoryWithEmbedding(
                tenantId, agentCode, "preference",
                "User Preferences",
                "User prefers dark mode",
                3, "run-" + testRunId, null);

        List<Map<String, Object>> memories = agentMemoryService.loadByImportance(tenantId, agentCode, 10);

        assertTrue(memories.size() >= 2,
                "At least 2 memories should have been stored for agent " + agentCode);
    }

    // ========== Test 2: trackAccess increments access_count ==========

    @Test
    @Order(2)
    void trackAccess_incrementsAccessCount() {
        Long tenantId = getTestTenant().getId();

        // Retrieve the initial access_count for a high-importance memory (importance=5 → tracked)
        List<Map<String, Object>> before = jdbcTemplate.queryForList(
                "SELECT access_count FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "AND memory_title = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "LIMIT 1",
                tenantId, agentCode, "System Architecture");

        assertFalse(before.isEmpty(), "Memory 'System Architecture' must exist from test 1");
        long countBefore = ((Number) before.get(0).get("access_count")).longValue();

        agentMemoryService.trackAccess(tenantId, agentCode);

        List<Map<String, Object>> after = jdbcTemplate.queryForList(
                "SELECT access_count FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "AND memory_title = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "LIMIT 1",
                tenantId, agentCode, "System Architecture");

        assertFalse(after.isEmpty(), "Memory should still exist after trackAccess");
        long countAfter = ((Number) after.get(0).get("access_count")).longValue();

        assertTrue(countAfter > countBefore,
                "access_count should have increased after trackAccess (was " + countBefore + ", now " + countAfter + ")");
    }

    // ========== Test 3: loadByImportance returns results ordered by importance DESC ==========

    @Test
    @Order(3)
    void loadByImportance_returnsOrderedByImportance() {
        Long tenantId = getTestTenant().getId();
        List<Map<String, Object>> memories = agentMemoryService.loadByImportance(tenantId, agentCode, 10);

        assertFalse(memories.isEmpty(), "loadByImportance should return at least 1 memory");

        for (int i = 1; i < memories.size(); i++) {
            int prev = ((Number) memories.get(i - 1).get("importance")).intValue();
            int curr = ((Number) memories.get(i).get("importance")).intValue();
            assertTrue(curr <= prev,
                    "Memories should be ordered by importance DESC at index " + i
                    + " (prev=" + prev + ", curr=" + curr + ")");
        }
    }

    // ========== Test 4: searchSemantic returns memories matching query keyword ==========

    @Test
    @Order(4)
    void searchSemantic_returnsRelatedMemories() {
        Long tenantId = getTestTenant().getId();
        List<Map<String, Object>> results = agentMemoryService.searchSemantic(
                tenantId, agentCode, "microservices", 5);

        assertFalse(results.isEmpty(),
                "Semantic search for 'microservices' should find at least 1 memory");

        boolean foundMicroservices = results.stream().anyMatch(m -> {
            String content = (String) m.get("memory_content");
            String title = (String) m.get("memory_title");
            return (content != null && content.contains("microservices"))
                    || (title != null && title.contains("microservices"));
        });
        assertTrue(foundMicroservices,
                "At least one result should be related to 'microservices'");
    }

    // ========== Test 5: deduplicateMemories removes lower-importance duplicate ==========

    @Test
    @Order(5)
    void deduplicateMemories_removesIdenticalContent() {
        Long tenantId = getTestTenant().getId();
        String duplicateTitle = "Duplicate Title " + testRunId;

        agentMemoryService.storeMemoryWithEmbedding(
                tenantId, agentCode, "fact", duplicateTitle, "Content A", 2, null, null);
        agentMemoryService.storeMemoryWithEmbedding(
                tenantId, agentCode, "fact", duplicateTitle, "Content B", 5, null, null);

        int removed = agentMemoryService.deduplicateMemories(tenantId, agentCode);

        assertEquals(1, removed,
                "Exactly 1 lower-importance duplicate should be removed");

        // Verify only one record with that title remains and it has importance=5
        List<Map<String, Object>> remaining = agentMemoryService.loadByImportance(tenantId, agentCode, 100);
        List<Map<String, Object>> duplicates = remaining.stream()
                .filter(m -> duplicateTitle.equals(m.get("memory_title")))
                .toList();

        assertEquals(1, duplicates.size(),
                "Only 1 record should remain with the duplicate title after deduplication");
        assertEquals(5, ((Number) duplicates.get(0).get("importance")).intValue(),
                "The surviving record should be the one with importance=5");
    }

    // ========== Test 6: memories are scoped to agentCode ==========

    @Test
    @Order(6)
    void listMemories_byAgentCode_returnsOnlyThatAgent() {
        Long tenantId = getTestTenant().getId();
        String otherAgentCode = "other-agent-" + testRunId;

        agentMemoryService.storeMemoryWithEmbedding(
                tenantId, otherAgentCode, "fact",
                "Other agent memory", "content", 3, null, null);

        // The test agent should NOT include the other agent's memory
        List<Map<String, Object>> testAgentMemories =
                agentMemoryService.loadByImportance(tenantId, agentCode, 100);

        boolean contaminated = testAgentMemories.stream()
                .anyMatch(m -> "Other agent memory".equals(m.get("memory_title")));
        assertFalse(contaminated,
                "Test agent memories should not contain memories from a different agent");

        // The other agent should see its own memory
        List<Map<String, Object>> otherAgentMemories =
                agentMemoryService.loadByImportance(tenantId, otherAgentCode, 100);
        boolean found = otherAgentMemories.stream()
                .anyMatch(m -> "Other agent memory".equals(m.get("memory_title")));
        assertTrue(found,
                "Other agent's memory should be retrievable under its own agentCode");
    }

    // ========== Test 7: storeMemoryWithEmbedding with null embedding inserts successfully ==========

    @Test
    @Order(7)
    void storeMemoryWithEmbedding_nullEmbedding_successfullyInserts() {
        Long tenantId = getTestTenant().getId();

        assertDoesNotThrow(() ->
                agentMemoryService.storeMemoryWithEmbedding(
                        tenantId, agentCode, "rule",
                        "No Embedding Rule", "Rule content",
                        4, "run-null-emb", null),
                "Storing a memory with null embedding should not throw");

        List<Map<String, Object>> memories = agentMemoryService.loadByImportance(tenantId, agentCode, 100);
        boolean rulePresent = memories.stream()
                .anyMatch(m -> "No Embedding Rule".equals(m.get("memory_title")));
        assertTrue(rulePresent,
                "The null-embedding memory should appear in loadByImportance results");
    }
}
