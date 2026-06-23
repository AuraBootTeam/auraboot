package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.entity.AgentEvalCase;
import com.auraboot.framework.agent.mapper.AgentEvalCaseMapper;
import com.auraboot.framework.agent.service.CapabilityEvalService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.service.impl.PluginResourceImporter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Import-time seam gate: verifies that {@code importAgentDefinition} persists
 * {@link com.auraboot.framework.agent.dto.CapabilityEvalCase} rows to
 * {@code ab_agent_eval_case} via a physical DELETE+INSERT (overwrite semantics).
 *
 * <p>Deterministic — no LLM key required. Exercises the importer + DB seam
 * in the same {@code integration-test} profile used by all other agent ITs.
 */
@DisplayName("AgentEvalCase import: importAgentDefinition persists and replaces eval cases")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentEvalCaseImportIT extends BaseIntegrationTest {

    private static final String AGENT_CODE = "eval_it_agent";
    private static final String CASE_ID = "eval-it-1";
    private static final String PLUGIN_PID = "test-eval-plugin-pid";

    @Autowired private PluginResourceImporter resourceImporter;
    @Autowired private AgentEvalCaseMapper evalCaseMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CapabilityEvalService capabilityEvalService;

    private Long tenantId;

    @BeforeEach
    void cleanSlate() {
        tenantId = getTestTenant().getId();
        jdbcTemplate.update(
                "DELETE FROM ab_agent_eval_case WHERE tenant_id = ? AND agent_code = ?",
                tenantId, AGENT_CODE);
        jdbcTemplate.update(
                "DELETE FROM ab_agent_definition WHERE tenant_id = ? AND agent_code = ?",
                tenantId, AGENT_CODE);
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            jdbcTemplate.update(
                    "DELETE FROM ab_agent_eval_case WHERE tenant_id = ? AND agent_code = ?",
                    tenantId, AGENT_CODE);
            jdbcTemplate.update(
                    "DELETE FROM ab_agent_definition WHERE tenant_id = ? AND agent_code = ?",
                    tenantId, AGENT_CODE);
        }
    }

    @Test
    @DisplayName("imports agent with one eval case; re-import replaces (DELETE+INSERT, no dup)")
    void importAgentWritesEvalCasesAndReimportReplaces() {
        // --- First import: one evalCase ---
        CapabilityEvalCase evalCase = CapabilityEvalCase.builder()
                .caseId(CASE_ID)
                .taskDescription("Query current alarms for device A")
                .expectedToolCodes(List.of("dsl.query"))
                .forbiddenToolCodes(List.of("x:write"))
                .category("test")
                .build();

        AgentDefinitionDTO dto = AgentDefinitionDTO.builder()
                .agentCode(AGENT_CODE)
                .name("Eval IT Agent")
                .description("Eval case import integration test agent")
                .agentType("reactive")
                .status("active")
                .evalCases(List.of(evalCase))
                .build();

        resourceImporter.importAgentDefinition(
                dto, PLUGIN_PID, "imp-1", tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        // Assert exactly one active row in ab_agent_eval_case
        List<AgentEvalCase> rows = evalCaseMapper.selectList(
                new LambdaQueryWrapper<AgentEvalCase>()
                        .eq(AgentEvalCase::getTenantId, tenantId)
                        .eq(AgentEvalCase::getAgentCode, AGENT_CODE)
                        .eq(AgentEvalCase::getDeletedFlag, false));

        assertEquals(1, rows.size(), "expected exactly 1 eval case row after first import");
        AgentEvalCase row = rows.get(0);
        assertNotNull(row.getPid(), "eval case row must have a pid");
        assertEquals(CASE_ID, row.getCaseId());
        assertEquals("test", row.getCategory());
        assertEquals("Query current alarms for device A", row.getTaskDescription());
        assertEquals(List.of("dsl.query"), row.getExpectedToolCodes());
        assertEquals(List.of("x:write"), row.getForbiddenToolCodes());
        assertEquals(PLUGIN_PID, row.getPluginSource());

        // --- Second import: same caseId but changed taskDescription ---
        CapabilityEvalCase updatedCase = CapabilityEvalCase.builder()
                .caseId(CASE_ID)
                .taskDescription("Query alarm history for device B (updated)")
                .expectedToolCodes(List.of("dsl.query"))
                .forbiddenToolCodes(List.of("x:write"))
                .category("test")
                .build();

        AgentDefinitionDTO dto2 = AgentDefinitionDTO.builder()
                .agentCode(AGENT_CODE)
                .name("Eval IT Agent")
                .description("Eval case import integration test agent")
                .agentType("reactive")
                .status("active")
                .evalCases(List.of(updatedCase))
                .build();

        resourceImporter.importAgentDefinition(
                dto2, PLUGIN_PID, "imp-2", tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        // Assert still exactly 1 active row with the new taskDescription (DELETE+INSERT, no dup)
        List<AgentEvalCase> rows2 = evalCaseMapper.selectList(
                new LambdaQueryWrapper<AgentEvalCase>()
                        .eq(AgentEvalCase::getTenantId, tenantId)
                        .eq(AgentEvalCase::getAgentCode, AGENT_CODE)
                        .eq(AgentEvalCase::getDeletedFlag, false));

        assertEquals(1, rows2.size(),
                "re-import must DELETE old cases and INSERT new ones — still exactly 1 row, no dup");
        assertEquals("Query alarm history for device B (updated)", rows2.get(0).getTaskDescription(),
                "taskDescription must reflect the updated case from the second import");
    }

    @Test
    @DisplayName("rollback soft-deletes eval cases; restore un-deletes them")
    void rollbackAndRestoreFollowsAgentLifecycle() {
        // Import agent with one eval case; capture the returned PluginResource
        CapabilityEvalCase evalCase = CapabilityEvalCase.builder()
                .caseId(CASE_ID)
                .taskDescription("Lifecycle test: query alarms")
                .expectedToolCodes(List.of("dsl.query"))
                .forbiddenToolCodes(List.of())
                .category("lifecycle")
                .build();

        AgentDefinitionDTO dto = AgentDefinitionDTO.builder()
                .agentCode(AGENT_CODE)
                .name("Lifecycle IT Agent")
                .description("Eval case lifecycle integration test")
                .agentType("reactive")
                .status("active")
                .evalCases(List.of(evalCase))
                .build();

        PluginResource pr = resourceImporter.importAgentDefinition(
                dto, PLUGIN_PID, "lifecycle-1", tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        // Confirm 1 active eval case exists before rollback
        int activeBeforeRollback = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_eval_case WHERE tenant_id = ? AND agent_code = ? AND deleted_flag = FALSE",
                Integer.class, tenantId, AGENT_CODE);
        assertEquals(1, activeBeforeRollback, "precondition: 1 active eval case before rollback");

        // Rollback: should soft-delete eval cases
        resourceImporter.rollbackResource(pr);

        int activeAfterRollback = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_eval_case WHERE tenant_id = ? AND agent_code = ? AND deleted_flag = FALSE",
                Integer.class, tenantId, AGENT_CODE);
        assertEquals(0, activeAfterRollback,
                "after rollback, eval cases must be soft-deleted (deleted_flag = TRUE)");

        // Restore: should un-delete eval cases
        resourceImporter.restoreResource(pr);

        int activeAfterRestore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_eval_case WHERE tenant_id = ? AND agent_code = ? AND deleted_flag = FALSE",
                Integer.class, tenantId, AGENT_CODE);
        assertEquals(1, activeAfterRestore,
                "after restore, eval cases must be active again (deleted_flag = FALSE)");
    }

    @Test
    @DisplayName("loadRegisteredCases returns eval case imported via importAgentDefinition")
    void loadRegisteredCasesReturnsImportedCases() {
        // Import agent with caseId "eval-it-1" and expectedToolCodes ["dsl.query"]
        CapabilityEvalCase evalCase = CapabilityEvalCase.builder()
                .caseId(CASE_ID)
                .taskDescription("Query current alarms for device A")
                .expectedToolCodes(List.of("dsl.query"))
                .forbiddenToolCodes(List.of("x:write"))
                .category("test")
                .build();

        AgentDefinitionDTO dto = AgentDefinitionDTO.builder()
                .agentCode(AGENT_CODE)
                .name("Eval IT Agent")
                .description("Eval case import integration test agent")
                .agentType("reactive")
                .status("active")
                .evalCases(List.of(evalCase))
                .build();

        resourceImporter.importAgentDefinition(
                dto, PLUGIN_PID, "load-1", tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        // Load via CapabilityEvalService.loadRegisteredCases
        List<CapabilityEvalCase> loaded = capabilityEvalService.loadRegisteredCases(tenantId);

        // Assert the imported case is present with correct expectedToolCodes
        boolean found = loaded.stream()
                .anyMatch(c -> CASE_ID.equals(c.getCaseId())
                        && List.of("dsl.query").equals(c.getExpectedToolCodes()));
        org.junit.jupiter.api.Assertions.assertTrue(found,
                "loadRegisteredCases must return case 'eval-it-1' with expectedToolCodes [\"dsl.query\"]; "
                        + "loaded=" + loaded.stream().map(CapabilityEvalCase::getCaseId).toList());
    }
}
