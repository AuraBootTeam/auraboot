package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentPromptAssemblyService;
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
 * Integration tests for AgentPromptAssemblyService.
 *
 * <p>Covers:
 * <ol>
 *   <li>Determinism — same inputs produce identical output across two calls</li>
 *   <li>Section ordering — IDENTITY always before USER CONTEXT</li>
 *   <li>Memory ordering — memories sorted by importance DESC within a category</li>
 * </ol>
 *
 * <p>Uses real PostgreSQL. Transaction propagation NOT_SUPPORTED so inserts are
 * visible to the service's own JdbcTemplate queries within the same test JVM.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentPromptAssemblyTest extends BaseIntegrationTest {

    @Autowired
    private AgentPromptAssemblyService assemblyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Unique per test-class run to avoid interference with other test executions. */
    private final String runId     = String.valueOf(System.currentTimeMillis());
    private final String agentCode = "prompt-assembly-test-" + runId;

    // =========================================================================
    // Test 1: Determinism — same inputs → identical output
    // =========================================================================

    @Test
    @Order(1)
    void assemblePrompt_sameInputsProduceIdenticalOutput() {
        Long tenantId = getTestTenant().getId();
        Long userId   = getTestUser().getId();

        // Seed a minimal agent definition
        insertAgentDefinition(tenantId, agentCode + "-det",
                "Determinism Bot",
                "Helps with determinism testing",
                "Methodical and precise",
                "Data analysis",
                "concise",
                "Always respond in the language of the question",
                "Deliver correct answers");

        // Seed one memory in the agent category
        insertMemory(tenantId, agentCode + "-det", "agent",
                "Core Knowledge", "The system is deterministic", 7);

        // Seed user profile
        insertUserProfile(tenantId, userId,
                "{\"style\": \"concise\"}", "{\"role\": \"tester\"}", null, null);

        // Call twice with identical inputs
        String result1 = assemblyService.assemblePrompt(tenantId, agentCode + "-det", userId);
        String result2 = assemblyService.assemblePrompt(tenantId, agentCode + "-det", userId);

        assertThat(result1).isNotBlank()
                .as("Assembled prompt must not be empty");
        assertThat(result1).isEqualTo(result2)
                .as("Same inputs must produce identical output (determinism)");
    }

    // =========================================================================
    // Test 2: Section ordering — IDENTITY section precedes USER CONTEXT section
    // =========================================================================

    @Test
    @Order(2)
    void assemblePrompt_identitySectionAppearsBeforeUserContextSection() {
        Long tenantId = getTestTenant().getId();
        Long userId   = getTestUser().getId();
        String code   = agentCode + "-order";

        insertAgentDefinition(tenantId, code,
                "Order Bot",
                "Tests section ordering",
                "Calm",
                "Ordering",
                "formal",
                null,
                "Maintain correct order");

        insertUserProfile(tenantId, userId,
                "{\"prefer\": \"short\"}", "{\"role\": \"qa\"}", null, "test-driven");

        String prompt = assemblyService.assemblePrompt(tenantId, code, userId);

        assertThat(prompt).isNotBlank();
        assertThat(prompt).contains("## IDENTITY");
        assertThat(prompt).contains("## USER CONTEXT");

        int identityIdx    = prompt.indexOf("## IDENTITY");
        int userContextIdx = prompt.indexOf("## USER CONTEXT");

        assertThat(identityIdx).isLessThan(userContextIdx)
                .as("IDENTITY section must appear before USER CONTEXT section");
    }

    // =========================================================================
    // Test 3: Memory ordering — importance DESC within a category
    // =========================================================================

    @Test
    @Order(3)
    void assemblePrompt_memoriesOrderedByImportanceDescending() {
        Long tenantId = getTestTenant().getId();
        String code   = agentCode + "-memorder";

        insertAgentDefinition(tenantId, code,
                "Memory Order Bot",
                "Tests memory ordering",
                "Analytical",
                "Memory management",
                "direct",
                null,
                "Recall facts in priority order");

        // Insert 3 agent-category memories with deliberately out-of-order importance
        insertMemory(tenantId, code, "agent", "Low Priority Fact",    "content-importance-3", 3);
        insertMemory(tenantId, code, "agent", "High Priority Fact",   "content-importance-7", 7);
        insertMemory(tenantId, code, "agent", "Medium Priority Fact", "content-importance-5", 5);

        String prompt = assemblyService.assemblePrompt(tenantId, code, null);

        assertThat(prompt).isNotBlank();
        assertThat(prompt).contains("content-importance-7");
        assertThat(prompt).contains("content-importance-5");
        assertThat(prompt).contains("content-importance-3");

        int idx7 = prompt.indexOf("content-importance-7");
        int idx5 = prompt.indexOf("content-importance-5");
        int idx3 = prompt.indexOf("content-importance-3");

        assertThat(idx7).isLessThan(idx5)
                .as("importance=7 memory must appear before importance=5");
        assertThat(idx5).isLessThan(idx3)
                .as("importance=5 memory must appear before importance=3");
    }

    // =========================================================================
    // Seed helpers
    // =========================================================================

    private void insertAgentDefinition(Long tenantId, String code, String name,
                                        String description, String personality,
                                        String expertise, String commStyle,
                                        String boundaries, String goals) {
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_definition "
                + "  (pid, tenant_id, agent_code, name, description, personality, expertise, "
                + "   communication_style, boundaries, soul_goals, status, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', NOW(), NOW(), FALSE)",
                pid, tenantId, code, name, description,
                personality, expertise, commStyle, boundaries, goals);
    }

    private void insertMemory(Long tenantId, String code, String category,
                               String title, String content, int importance) {
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory "
                + "  (pid, tenant_id, memory_agent_id, memory_type, category, "
                + "   memory_title, memory_content, importance, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, 'fact', ?, ?, ?, ?, NOW(), NOW(), FALSE)",
                pid, tenantId, code, category, title, content, importance);
    }

    private void insertUserProfile(Long tenantId, Long userId,
                                    String communicationJson, String roleContextJson,
                                    String preferencesJson, String decisionPatterns) {
        // Use upsert to avoid unique constraint violations if user profile already exists
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_user_profile "
                + "  (pid, tenant_id, user_id, communication, role_context, preferences, "
                + "   decision_patterns, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, NOW(), NOW(), FALSE) "
                + "ON CONFLICT ON CONSTRAINT uq_ab_agent_user_profile_user DO UPDATE SET "
                + "  communication     = COALESCE(EXCLUDED.communication, ab_agent_user_profile.communication), "
                + "  role_context      = COALESCE(EXCLUDED.role_context, ab_agent_user_profile.role_context), "
                + "  preferences       = COALESCE(EXCLUDED.preferences, ab_agent_user_profile.preferences), "
                + "  decision_patterns = COALESCE(EXCLUDED.decision_patterns, ab_agent_user_profile.decision_patterns), "
                + "  updated_at        = NOW()",
                pid, tenantId, userId,
                communicationJson, roleContextJson, preferencesJson, decisionPatterns);
    }

}
