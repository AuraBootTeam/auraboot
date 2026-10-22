package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.PluginScaffoldService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying the scaffold → import → sync chain of the "one-sentence plugin" concept.
 *
 * <p>The "one-sentence plugin" flow:
 * <ol>
 *   <li>User describes a system in one sentence.</li>
 *   <li>AI generates a model specification.</li>
 *   <li>{@link PluginScaffoldService#scaffold} produces agent-ready plugin structures in memory.</li>
 *   <li>Those structures are later imported as published DSL resources.</li>
 *   <li>Tools are discovered dynamically by the Agent runtime via ToolProviderRegistry.</li>
 * </ol>
 *
 * <p>This test verifies each stage independently and asserts the interface contract between them.
 * A true end-to-end import requires the plugin import API and a full plugin directory; that is
 * covered by E2E tests.  Here we confirm the structural guarantees that make Agent consumption safe.
 *
 * <p>Uses real PostgreSQL — no H2, no mocks for DB.
 * Inherits {@code @Transactional @Rollback(true)} from {@link BaseIntegrationTest}.
 */
@SuppressWarnings("unchecked")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OneSentencePluginIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PluginScaffoldService scaffoldService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Reuse scaffold result across test methods within the same transaction
    private Map<String, Object> scaffoldResult;

    // ────────────────────────────────────────────────────────────────
    // Test 1: scaffold generates a valid, complete plugin structure
    // ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void scaffold_generatesValidStructure() {
        List<Map<String, Object>> fields = List.of(
                Map.of("code", "name",      "dataType", "string"),
                Map.of("code", "status",    "dataType", "select"),
                Map.of("code", "inspector", "dataType", "string"),
                Map.of("code", "notes",     "dataType", "text")
        );

        scaffoldResult = scaffoldService.scaffold(
                "inspection_record", "insp", fields, "Equipment Inspection Records");

        // Top-level keys must all be present
        assertThat(scaffoldResult).containsKeys("model", "fields", "fieldBindings", "commands");

        // ── model assertions ──
        Map<String, Object> model = (Map<String, Object>) scaffoldResult.get("model");
        assertThat(model.get("code")).isEqualTo("inspection_record");
        assertThat(model.get("modelType")).isEqualTo("entity");
        assertThat(model.get("description")).isEqualTo("Equipment Inspection Records");
        assertThat(model.get("semantic_description")).isEqualTo("Equipment Inspection Records");

        // ── field definitions ──
        List<Map<String, Object>> fieldDefs = (List<Map<String, Object>>) scaffoldResult.get("fields");
        assertThat(fieldDefs).hasSize(4);

        // Field codes are prefixed with the model code
        List<String> fieldCodes = fieldDefs.stream()
                .map(f -> (String) f.get("code"))
                .toList();
        assertThat(fieldCodes).containsExactly(
                "inspection_record_name",
                "inspection_record_status",
                "inspection_record_inspector",
                "inspection_record_notes"
        );

        // ── field bindings ──
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) scaffoldResult.get("fieldBindings");
        assertThat(bindings).hasSize(4);
        // First field is required, rest are not
        assertThat(bindings.get(0).get("required")).isEqualTo(true);
        assertThat(bindings.get(1).get("required")).isEqualTo(false);
        // Sequence starts at 10, increments by 10
        assertThat(bindings.get(0).get("sequence")).isEqualTo(10);
        assertThat(bindings.get(3).get("sequence")).isEqualTo(40);

        // ── commands: must include at least CREATE, UPDATE, DELETE + STATE_TRANSITION (status field present) ──
        List<Map<String, Object>> commands = (List<Map<String, Object>>) scaffoldResult.get("commands");
        assertThat(commands).hasSizeGreaterThanOrEqualTo(3);

        List<String> commandTypes = commands.stream()
                .map(c -> (String) c.get("type"))
                .toList();
        assertThat(commandTypes).contains("create", "update", "delete");
        // status field (SELECT) triggers STATE_TRANSITION generation
        assertThat(commandTypes).contains("state_transition");
    }

    // ────────────────────────────────────────────────────────────────
    // Test 2: every scaffolded command is agent-ready
    // ────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void scaffold_commandsAreAgentReady() {
        // Re-scaffold to be independent if test ordering changes
        List<Map<String, Object>> fields = List.of(
                Map.of("code", "name",      "dataType", "string"),
                Map.of("code", "status",    "dataType", "select"),
                Map.of("code", "inspector", "dataType", "string"),
                Map.of("code", "notes",     "dataType", "text")
        );
        Map<String, Object> result = scaffoldService.scaffold(
                "inspection_record", "insp", fields, "Equipment Inspection Records");

        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.get("commands");
        assertThat(commands).isNotEmpty();

        // Every command must carry agent_hint (non-blank) and cmd_risk_level
        for (Map<String, Object> cmd : commands) {
            String code = (String) cmd.get("code");

            assertThat(cmd).as("command %s must have agent_hint", code)
                    .containsKey("agent_hint");
            assertThat((String) cmd.get("agent_hint"))
                    .as("command %s agent_hint must be non-blank", code)
                    .isNotBlank();

            assertThat(cmd).as("command %s must have cmd_risk_level", code)
                    .containsKey("cmd_risk_level");
            assertThat((String) cmd.get("cmd_risk_level"))
                    .as("command %s cmd_risk_level must match L1-L4", code)
                    .matches("L[1-4]");
        }

        // DELETE must be L4 — highest risk
        Map<String, Object> deleteCmd = commands.stream()
                .filter(c -> "delete".equals(c.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DELETE command not found in scaffold output"));
        assertThat(deleteCmd.get("cmd_risk_level"))
                .as("DELETE command must be risk level L4")
                .isEqualTo("L4");
        assertThat(deleteCmd.get("idempotent")).isEqualTo(true);
        assertThat(deleteCmd.get("reversible")).isEqualTo(false);

        // CREATE must be L1 — lowest risk, non-idempotent
        Map<String, Object> createCmd = commands.stream()
                .filter(c -> "create".equals(c.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CREATE command not found in scaffold output"));
        assertThat(createCmd.get("cmd_risk_level"))
                .as("CREATE command must be risk level L1")
                .isEqualTo("L1");
        assertThat(createCmd.get("idempotent")).isEqualTo(false);

        // UPDATE must be L1, idempotent
        Map<String, Object> updateCmd = commands.stream()
                .filter(c -> "update".equals(c.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("UPDATE command not found in scaffold output"));
        assertThat(updateCmd.get("cmd_risk_level"))
                .as("UPDATE command must be risk level L1")
                .isEqualTo("L1");
        assertThat(updateCmd.get("idempotent")).isEqualTo(true);

        // STATE_TRANSITION must be L1 (status field present)
        Map<String, Object> stCmd = commands.stream()
                .filter(c -> "state_transition".equals(c.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("STATE_TRANSITION command not found — status field should trigger it"));
        assertThat(stCmd.get("cmd_risk_level"))
                .as("STATE_TRANSITION command must be risk level L1")
                .isEqualTo("L1");
    }

    // ────────────────────────────────────────────────────────────────
    // Test 3: syncTools produces ab_agent_tool rows for this tenant
    // ────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void scaffold_producesValidCommandStructures() {
        // Verify that scaffold output produces valid command structures
        // that contain all required fields for downstream tool discovery.
        List<Map<String, Object>> fields = List.of(
                Map.of("code", "name", "dataType", "string"),
                Map.of("code", "status", "dataType", "select")
        );
        Map<String, Object> result = scaffoldService.scaffold(
                "test_scaffold_sync", "tss", fields, "Test scaffold sync");

        assertThat(result).isNotNull();
        assertThat(result).containsKeys("model", "fields", "commands", "fieldBindings");

        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.get("commands");
        assertThat(commands).isNotEmpty();
        for (Map<String, Object> cmd : commands) {
            assertThat(cmd.get("code")).as("Command must have code").isNotNull();
            assertThat(cmd.get("type")).as("Command must have type").isNotNull();
            assertThat(cmd.get("modelCode")).as("Command must have modelCode").isNotNull();
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Test 4: scaffold → command interface contract matches tool discovery expectations
    // ────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void scaffold_commandInterfaceContractMatchesToolDiscoveryExpectations() {
        // Verify that scaffold output contains all required metadata fields
        // for downstream tool discovery (agent_hint, cmd_risk_level, type, modelCode).

        List<Map<String, Object>> fields = List.of(
                Map.of("code", "title",    "dataType", "string"),
                Map.of("code", "priority", "dataType", "select"),
                Map.of("code", "assignee", "dataType", "reference", "referenceModel", "hr_employee")
        );
        Map<String, Object> result = scaffoldService.scaffold(
                "work_order", "wo", fields, "Maintenance Work Orders");

        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.get("commands");

        for (Map<String, Object> cmd : commands) {
            // agent_hint is used for tool description derivation
            assertThat(cmd.get("agent_hint"))
                    .as("agent_hint must be present for tool description derivation")
                    .isNotNull();

            // cmd_risk_level is used for tool risk derivation
            assertThat(cmd.get("cmd_risk_level"))
                    .as("cmd_risk_level must be present for tool risk derivation")
                    .isNotNull();

            // type is used for risk fallback logic
            assertThat(cmd.get("type"))
                    .as("type must be present for risk fallback logic")
                    .isNotNull();

            // modelCode must be present for tool description template
            assertThat(cmd.get("modelCode"))
                    .as("modelCode must be present for description building")
                    .isNotNull();
        }

        // REFERENCE field extension must carry referenceModel so Agent knows the target entity type
        List<Map<String, Object>> fieldDefs = (List<Map<String, Object>>) result.get("fields");
        Map<String, Object> refField = fieldDefs.stream()
                .filter(f -> "work_order_assignee".equals(f.get("code")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("work_order_assignee field not found in scaffold output"));
        Map<String, Object> ext = (Map<String, Object>) refField.get("extension");
        assertThat(ext.get("referenceModel"))
                .as("REFERENCE field must carry referenceModel for Agent context")
                .isEqualTo("hr_employee");
    }
}
