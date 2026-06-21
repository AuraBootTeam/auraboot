package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.service.CapabilityEvalService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.service.impl.PluginResourceImporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-plugin coexistence regression IT.
 *
 * <p>Proves that two plugins each registering an agent with eval cases:
 * <ol>
 *   <li>Coexist — both agents' cases visible via {@code loadRegisteredCases}</li>
 *   <li>Rollback of one plugin does NOT affect the other (isolation)</li>
 *   <li>A case whose {@code expectedToolCodes} are absent from the tenant catalog
 *       is marked {@code unavailable} (D3a dependency skip), NOT counted as failed</li>
 * </ol>
 *
 * <p>No LLM key required. Uses keyword eval mode. Deterministic.
 */
@DisplayName("Multi-plugin eval case coexistence: isolation + D3a dependency skip")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MultiPluginEvalCaseCoexistenceIT extends BaseIntegrationTest {

    private static final String AGENT_A_CODE   = "eval_it_agent_a";
    private static final String AGENT_B_CODE   = "eval_it_agent_b";
    private static final String PLUGIN_A_PID   = "plugin-a";
    private static final String PLUGIN_B_PID   = "plugin-b";
    private static final String CASE_ID_A      = "a-1";
    private static final String CASE_ID_B      = "b-1";
    /** A tool code that will never appear in the bare :test context catalog. */
    private static final String UNAVAILABLE_TOOL = "nonexistent.plugin.tool.xyz";

    @Autowired private PluginResourceImporter resourceImporter;
    @Autowired private CapabilityEvalService  capabilityEvalService;
    @Autowired private JdbcTemplate           jdbcTemplate;

    private Long tenantId;

    @BeforeEach
    void cleanSlate() {
        tenantId = getTestTenant().getId();
        deleteTestData();
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            deleteTestData();
        }
    }

    private void deleteTestData() {
        for (String agentCode : List.of(AGENT_A_CODE, AGENT_B_CODE)) {
            jdbcTemplate.update(
                    "DELETE FROM ab_agent_eval_case WHERE tenant_id = ? AND agent_code = ?",
                    tenantId, agentCode);
            jdbcTemplate.update(
                    "DELETE FROM ab_agent_definition WHERE tenant_id = ? AND agent_code = ?",
                    tenantId, agentCode);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper builders
    // ─────────────────────────────────────────────────────────────────────────

    private AgentDefinitionDTO agentDto(String agentCode, String name, List<CapabilityEvalCase> cases) {
        return AgentDefinitionDTO.builder()
                .agentCode(agentCode)
                .name(name)
                .description("Multi-plugin coexistence IT: " + name)
                .agentType("reactive")
                .status("active")
                .evalCases(cases)
                .build();
    }

    private CapabilityEvalCase evalCase(String caseId, String description, List<String> expectedTools) {
        return CapabilityEvalCase.builder()
                .caseId(caseId)
                .taskDescription(description)
                .expectedToolCodes(expectedTools)
                .forbiddenToolCodes(List.of())
                .category("coexistence-test")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main test: coexistence + rollback isolation + D3a dependency skip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Two plugin agents coexist; rolling back one does not touch the other; "
            + "unavailable tool code is skipped (D3a), not failed")
    void twoPluginsCoexistRollbackIsolatedDependencySkip() {

        // ── Step 1: import agent_a (plugin-a) with case a-1 ─────────────────
        CapabilityEvalCase caseA = evalCase(
                CASE_ID_A,
                "Query current alarm list for plugin A device",
                List.of("dsl.query"));

        PluginResource prA = resourceImporter.importAgentDefinition(
                agentDto(AGENT_A_CODE, "Plugin A Agent", List.of(caseA)),
                PLUGIN_A_PID, "coex-a-1", tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        assertNotNull(prA, "importAgentDefinition must return a non-null PluginResource for agent_a");

        // ── Step 2: import agent_b (plugin-b) with case b-1 ─────────────────
        CapabilityEvalCase caseB = evalCase(
                CASE_ID_B,
                "Query device status for plugin B sensor",
                List.of("dsl.query"));

        PluginResource prB = resourceImporter.importAgentDefinition(
                agentDto(AGENT_B_CODE, "Plugin B Agent", List.of(caseB)),
                PLUGIN_B_PID, "coex-b-1", tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        assertNotNull(prB, "importAgentDefinition must return a non-null PluginResource for agent_b");

        // ── Step 3: loadRegisteredCases must contain BOTH a-1 and b-1 ────────
        List<CapabilityEvalCase> loaded = capabilityEvalService.loadRegisteredCases(tenantId);
        List<String> loadedIds = loaded.stream().map(CapabilityEvalCase::getCaseId).toList();

        assertTrue(loadedIds.contains(CASE_ID_A),
                "loadRegisteredCases must contain case a-1; loaded caseIds=" + loadedIds);
        assertTrue(loadedIds.contains(CASE_ID_B),
                "loadRegisteredCases must contain case b-1; loaded caseIds=" + loadedIds);

        // Verify raw DB counts for both agents before rollback
        int aActiveBeforeRollback = countActive(AGENT_A_CODE);
        int bActiveBeforeRollback = countActive(AGENT_B_CODE);
        assertEquals(1, aActiveBeforeRollback, "precondition: 1 active eval case for agent_a before rollback");
        assertEquals(1, bActiveBeforeRollback, "precondition: 1 active eval case for agent_b before rollback");

        // ── Step 4: rollback agent_a → a-1 inactive; b-1 STILL active ────────
        // This is the multi-plugin isolation proof: rollback must be scoped to (tenant_id, agent_code).
        resourceImporter.rollbackResource(prA);

        int aActiveAfterRollback = countActive(AGENT_A_CODE);
        int bActiveAfterRollback = countActive(AGENT_B_CODE);

        assertEquals(0, aActiveAfterRollback,
                "ISOLATION FAIL: after rolling back plugin-a, agent_a eval cases must be soft-deleted "
                        + "(deleted_flag=TRUE); found " + aActiveAfterRollback + " still active");
        assertEquals(1, bActiveAfterRollback,
                "ISOLATION FAIL: rolling back plugin-a must NOT affect plugin-b's eval cases; "
                        + "expected 1 active eval case for agent_b but found " + bActiveAfterRollback);

        // Confirm via loadRegisteredCases that the service layer also sees the correct state
        List<CapabilityEvalCase> loadedAfterRollback = capabilityEvalService.loadRegisteredCases(tenantId);
        List<String> loadedIdsAfterRollback = loadedAfterRollback.stream()
                .map(CapabilityEvalCase::getCaseId).toList();

        assertFalse(loadedIdsAfterRollback.contains(CASE_ID_A),
                "loadRegisteredCases after rollback must NOT return a-1; loaded=" + loadedIdsAfterRollback);
        assertTrue(loadedIdsAfterRollback.contains(CASE_ID_B),
                "loadRegisteredCases after rollback must still return b-1; loaded=" + loadedIdsAfterRollback);

        // ── Step 5: D3a dependency skip — tool not in tenant catalog → unavailable ────
        // In the bare :test context the tool provider catalog is empty, so any case whose
        // expectedToolCodes contain a non-platform tool will be unavailable, not failed.
        CapabilityEvalCase unavailableCase = evalCase(
                "skip-1",
                "Perform an operation that requires a plugin-specific tool not installed",
                List.of(UNAVAILABLE_TOOL));

        // evaluateToolSelection: keyword mode; all-unavailable run must short-circuit to
        // no_scoreable_cases — NOT persisted, NOT gate-eligible, per the Fix B contract.
        Map<String, Object> report = capabilityEvalService.evaluateToolSelection(
                tenantId, "keyword", List.of(unavailableCase));

        // Fix B: all-unavailable → no_scoreable_cases (mirrors no_cases contract, not persisted)
        assertEquals("no_scoreable_cases", report.get("status"),
                "D3a/Fix-B: all-unavailable run must return status=no_scoreable_cases; report=" + report);

        Integer unavailableCases = (Integer) report.get("unavailableCases");
        Integer totalCases       = (Integer) report.get("totalCases");

        assertNotNull(unavailableCases, "report must contain 'unavailableCases' key (D3a)");
        assertNotNull(totalCases,       "report must contain 'totalCases' key");

        assertEquals(1, unavailableCases,
                "D3a: the case with non-catalog expectedToolCode must be counted in unavailableCases=1; "
                        + "report=" + report);
        assertEquals(0, totalCases,
                "D3a: unavailable case must NOT be included in the scoreable denominator (totalCases=0); "
                        + "report=" + report);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private int countActive(String agentCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_eval_case "
                        + "WHERE tenant_id = ? AND agent_code = ? AND deleted_flag = FALSE",
                Integer.class, tenantId, agentCode);
        return count != null ? count : 0;
    }
}
