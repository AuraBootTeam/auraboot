package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.PluginScaffoldService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for PluginScaffoldService.
 * Verifies that scaffold() generates correct in-memory plugin JSON structures.
 */
@SuppressWarnings("unchecked")
class PluginScaffoldServiceTest extends BaseIntegrationTest {

    @Autowired
    private PluginScaffoldService scaffoldService;

    /**
     * Test 1: scaffold generates model + fields + commands + bindings with correct structure.
     */
    @Test
    void scaffold_generatesCompleteStructure() {
        List<Map<String, Object>> fields = List.of(
                Map.of("code", "name", "dataType", "string"),
                Map.of("code", "amount", "dataType", "decimal"),
                Map.of("code", "status", "dataType", "select")
        );

        Map<String, Object> result = scaffoldService.scaffold(
                "insp_equipment", "insp", fields, "Equipment inspection record");

        // Top-level keys
        assertThat(result).containsKeys("model", "fields", "fieldBindings", "commands");

        // Model assertions
        Map<String, Object> model = (Map<String, Object>) result.get("model");
        assertThat(model.get("code")).isEqualTo("insp_equipment");
        assertThat(model.get("modelType")).isEqualTo("entity");
        assertThat(model.get("semantic_description")).isEqualTo("Equipment inspection record");

        // Fields assertions
        List<Map<String, Object>> fieldDefs = (List<Map<String, Object>>) result.get("fields");
        assertThat(fieldDefs).hasSize(3);
        assertThat(fieldDefs.get(0).get("code")).isEqualTo("insp_equipment_name");
        assertThat(fieldDefs.get(0).get("dataType")).isEqualTo("STRING");
        assertThat(fieldDefs.get(1).get("code")).isEqualTo("insp_equipment_amount");
        assertThat(fieldDefs.get(1).get("dataType")).isEqualTo("DECIMAL");

        // Field bindings assertions
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) result.get("fieldBindings");
        assertThat(bindings).hasSize(3);
        assertThat(bindings.get(0).get("required")).isEqualTo(true);
        assertThat(bindings.get(1).get("required")).isEqualTo(false);
        assertThat(bindings.get(0).get("sequence")).isEqualTo(10);
        assertThat(bindings.get(1).get("sequence")).isEqualTo(20);

        // Because status/SELECT field exists, should generate 4 commands (CRUD + STATE_TRANSITION)
        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.get("commands");
        assertThat(commands).hasSize(4);
        List<String> commandCodes = commands.stream()
                .map(c -> (String) c.get("code"))
                .toList();
        assertThat(commandCodes).contains(
                "insp:create_equipment",
                "insp:update_equipment",
                "insp:delete_equipment",
                "insp:change_status_equipment"
        );
    }

    /**
     * Test 2: generated commands have agent_hint and cmd_risk_level with correct values.
     */
    @Test
    void scaffold_commandsHaveAgentReadyFields() {
        List<Map<String, Object>> fields = List.of(
                Map.of("code", "title", "dataType", "string"),
                Map.of("code", "customer", "dataType", "reference", "referenceModel", "crm_customer")
        );

        Map<String, Object> result = scaffoldService.scaffold(
                "order_request", "ord", fields, "Customer order request");

        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.get("commands");
        assertThat(commands).isNotEmpty();

        for (Map<String, Object> cmd : commands) {
            // Every command must carry agent_hint and cmd_risk_level
            assertThat(cmd).containsKey("agent_hint");
            assertThat(cmd).containsKey("cmd_risk_level");
            assertThat((String) cmd.get("agent_hint")).isNotBlank();
            assertThat((String) cmd.get("cmd_risk_level")).matches("L[1-4]");
        }

        // CREATE: L1, idempotent=false, reversible=false
        Map<String, Object> createCmd = commands.stream()
                .filter(c -> "create".equals(c.get("type")))
                .findFirst().orElseThrow();
        assertThat(createCmd.get("cmd_risk_level")).isEqualTo("L1");
        assertThat(createCmd.get("idempotent")).isEqualTo(false);
        assertThat(createCmd.get("reversible")).isEqualTo(false);

        // UPDATE: L1, idempotent=true, reversible=true
        Map<String, Object> updateCmd = commands.stream()
                .filter(c -> "update".equals(c.get("type")))
                .findFirst().orElseThrow();
        assertThat(updateCmd.get("cmd_risk_level")).isEqualTo("L1");
        assertThat(updateCmd.get("idempotent")).isEqualTo(true);
        assertThat(updateCmd.get("reversible")).isEqualTo(true);

        // DELETE: L4, idempotent=true, reversible=false
        Map<String, Object> deleteCmd = commands.stream()
                .filter(c -> "delete".equals(c.get("type")))
                .findFirst().orElseThrow();
        assertThat(deleteCmd.get("cmd_risk_level")).isEqualTo("L4");
        assertThat(deleteCmd.get("idempotent")).isEqualTo(true);
        assertThat(deleteCmd.get("reversible")).isEqualTo(false);

        // REFERENCE field should carry referenceModel in extension
        List<Map<String, Object>> fieldDefs = (List<Map<String, Object>>) result.get("fields");
        Map<String, Object> refField = fieldDefs.stream()
                .filter(f -> "order_request_customer".equals(f.get("code")))
                .findFirst().orElseThrow();
        Map<String, Object> ext = (Map<String, Object>) refField.get("extension");
        assertThat(ext.get("referenceModel")).isEqualTo("crm_customer");
    }

    /**
     * Test 3: empty fields list → still generates minimum 3 CRUD commands.
     */
    @Test
    void scaffold_emptyFields_stillGeneratesCrudCommands() {
        Map<String, Object> result = scaffoldService.scaffold(
                "asset_tag", "ast", List.of(), null);

        // Model is still generated
        Map<String, Object> model = (Map<String, Object>) result.get("model");
        assertThat(model.get("code")).isEqualTo("asset_tag");
        assertThat(model.get("modelType")).isEqualTo("entity");

        // No fields, no bindings
        List<?> fieldDefs = (List<?>) result.get("fields");
        assertThat(fieldDefs).isEmpty();
        List<?> bindings = (List<?>) result.get("fieldBindings");
        assertThat(bindings).isEmpty();

        // But CRUD commands must still be generated (CREATE, UPDATE, DELETE = 3)
        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.get("commands");
        assertThat(commands).hasSizeGreaterThanOrEqualTo(3);

        List<String> types = commands.stream()
                .map(c -> (String) c.get("type"))
                .toList();
        assertThat(types).contains("create", "update", "delete");

        // Commands still have agent_hint and cmd_risk_level even with empty fields
        for (Map<String, Object> cmd : commands) {
            assertThat(cmd).containsKey("agent_hint");
            assertThat(cmd).containsKey("cmd_risk_level");
        }

        // Default description should be auto-generated when null is passed
        String desc = (String) model.get("description");
        assertThat(desc).isNotBlank();
    }
}
