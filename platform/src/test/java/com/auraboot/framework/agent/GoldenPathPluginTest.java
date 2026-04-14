package com.auraboot.framework.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural tests for the Golden Path plugin template.
 * Validates that all expected JSON files exist and contain the required
 * agent-ready semantic fields (semantic_description, domain_category,
 * data_sensitivity, lifecycle_description, agent_hint, cmd_risk_level, etc.).
 *
 * This test class does NOT require a Spring context — it only reads files from
 * the filesystem using Jackson. The golden-path directory is located relative
 * to the repo root: plugins/templates/golden-path/
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GoldenPathPluginTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Resolve the golden-path directory by walking up from the working directory
     * until we find the AuraBoot repo root (identified by the presence of
     * plugins/templates/golden-path).
     */
    private Path goldenPathDir;

    @BeforeAll
    void resolveGoldenPathDirectory() {
        // Try well-known paths first (CI, local dev)
        String[] candidates = {
            // From repo root
            "plugins/templates/golden-path",
            // From platform/ subproject
            "../plugins/templates/golden-path",
        };

        for (String candidate : candidates) {
            Path path = Paths.get(candidate).toAbsolutePath().normalize();
            if (path.toFile().isDirectory() && path.resolve("plugin.json").toFile().exists()) {
                goldenPathDir = path;
                break;
            }
        }

        assertNotNull(goldenPathDir,
                "Could not locate plugins/templates/golden-path directory. "
                + "Tried: " + String.join(", ", candidates));
    }

    // ========== Test 1: All expected files exist ==========

    @Test
    @Order(1)
    void goldenPathPlugin_jsonFilesExist() {
        List<String> expectedFiles = List.of(
                "plugin.json",
                "config/models.json",
                "config/dicts.json",
                "config/permissions.json",
                "config/menus.json",
                "config/i18n.json",
                "config/named-queries.json",
                "config/commands/gp_task.json",
                "config/commands/gp_approval.json",
                "config/commands/gp_task_comment.json",
                "config/fields/gp_task.json",
                "config/fields/gp_approval.json",
                "config/bindings/gp_task.json",
                "config/bindings/gp_approval.json"
        );

        for (String relativePath : expectedFiles) {
            File file = goldenPathDir.resolve(relativePath).toFile();
            assertTrue(file.exists() && file.isFile(),
                    "Expected file missing: " + relativePath);
        }
    }

    // ========== Test 2: models.json has semantic fields on all models ==========

    @Test
    @Order(2)
    void goldenPathModels_haveSemanticFields() throws IOException {
        JsonNode models = MAPPER.readTree(goldenPathDir.resolve("config/models.json").toFile());
        assertTrue(models.isArray() && models.size() > 0,
                "models.json must be a non-empty JSON array");

        List<String> requiredSemanticFields = List.of(
                "semantic_description",
                "domain_category",
                "data_sensitivity",
                "lifecycle_description"
        );

        for (JsonNode model : models) {
            String code = model.path("code").asText("(unknown)");
            for (String field : requiredSemanticFields) {
                assertTrue(model.has(field) && !model.path(field).isNull()
                                && !model.path(field).asText("").isBlank(),
                        "Model '" + code + "' is missing required semantic field: " + field);
            }
        }
    }

    // ========== Test 3: gp_task commands have full agent semantics ==========

    @Test
    @Order(3)
    void goldenPathCommands_haveFullSemantics() throws IOException {
        JsonNode commands = MAPPER.readTree(
                goldenPathDir.resolve("config/commands/gp_task.json").toFile());
        assertTrue(commands.isArray() && commands.size() > 0,
                "gp_task commands must be a non-empty JSON array");

        List<String> requiredCommandFields = List.of(
                "agent_hint",
                "cmd_risk_level",
                "precondition_description"
        );

        for (JsonNode cmd : commands) {
            String code = cmd.path("code").asText("(unknown)");
            for (String field : requiredCommandFields) {
                assertTrue(cmd.has(field) && !cmd.path(field).isNull(),
                        "Command '" + code + "' is missing required semantic field: " + field);
                // agent_hint must be a non-blank string
                if ("agent_hint".equals(field)) {
                    assertFalse(cmd.path(field).asText("").isBlank(),
                            "Command '" + code + "'.agent_hint must not be blank");
                }
            }
        }
    }

    // ========== Test 4: gp_approval commands have correct risk levels ==========

    @Test
    @Order(4)
    void goldenPathApproval_hasCorrectRiskLevels() throws IOException {
        JsonNode commands = MAPPER.readTree(
                goldenPathDir.resolve("config/commands/gp_approval.json").toFile());
        assertTrue(commands.isArray() && commands.size() > 0,
                "gp_approval commands must be a non-empty array");

        Map<String, String> expectedRisk = Map.of(
                "gp:request_approval", "L3",
                "gp:approve",           "L2",
                "gp:reject",            "L2"
        );

        for (JsonNode cmd : commands) {
            String code = cmd.path("code").asText();
            if (expectedRisk.containsKey(code)) {
                String actualRisk = cmd.path("cmd_risk_level").asText("");
                assertEquals(expectedRisk.get(code), actualRisk,
                        "Command '" + code + "' must have cmd_risk_level="
                        + expectedRisk.get(code) + " but got: " + actualRisk);
            }
        }

        // Also verify all three commands are present
        List<String> foundCodes = new ArrayList<>();
        for (JsonNode cmd : commands) {
            foundCodes.add(cmd.path("code").asText());
        }
        for (String expectedCode : expectedRisk.keySet()) {
            assertTrue(foundCodes.contains(expectedCode),
                    "Expected command '" + expectedCode + "' not found in gp_approval commands");
        }
    }

    // ========== Test 5: plugin.json is valid and lists gp_approval in provides ==========

    @Test
    @Order(5)
    void goldenPathPlugin_validJson() throws IOException {
        JsonNode plugin = MAPPER.readTree(goldenPathDir.resolve("plugin.json").toFile());

        // Basic structure checks
        assertFalse(plugin.path("pluginId").asText("").isBlank(),
                "pluginId must be present");
        assertFalse(plugin.path("namespace").asText("").isBlank(),
                "namespace must be present");
        assertFalse(plugin.path("version").asText("").isBlank(),
                "version must be present");

        // The provides list must include the gp_approval model
        JsonNode provides = plugin.path("provides");
        assertTrue(provides.isArray() && provides.size() > 0,
                "provides must be a non-empty array");

        boolean hasApprovalModel = false;
        boolean hasApprovalCommand = false;
        for (JsonNode item : provides) {
            String type = item.path("type").asText();
            String code = item.path("code").asText();
            if ("model".equals(type) && "gp_approval".equals(code)) hasApprovalModel = true;
            if ("command".equals(type) && "gp:request_approval".equals(code)) hasApprovalCommand = true;
        }
        assertTrue(hasApprovalModel,
                "plugin.json provides list must include {type:model, code:gp_approval}");
        assertTrue(hasApprovalCommand,
                "plugin.json provides list must include {type:command, code:gp:request_approval}");
    }

    // ========== Test 6: dicts.json contains gp_approval_status with 3 items ==========

    @Test
    @Order(6)
    void goldenPathDicts_hasApprovalStatus() throws IOException {
        JsonNode dicts = MAPPER.readTree(goldenPathDir.resolve("config/dicts.json").toFile());
        assertTrue(dicts.isArray() && dicts.size() > 0,
                "dicts.json must be a non-empty JSON array");

        JsonNode approvalStatusDict = null;
        for (JsonNode dict : dicts) {
            if ("gp_approval_status".equals(dict.path("code").asText())) {
                approvalStatusDict = dict;
                break;
            }
        }

        assertNotNull(approvalStatusDict,
                "dicts.json must contain a dict with code 'gp_approval_status'");

        JsonNode items = approvalStatusDict.path("items");
        assertTrue(items.isArray(),
                "gp_approval_status dict must have an 'items' array");
        assertEquals(3, items.size(),
                "gp_approval_status must have exactly 3 items (PENDING, APPROVED, REJECTED)");

        // Verify the 3 expected status values are present
        List<String> values = new ArrayList<>();
        for (JsonNode item : items) {
            values.add(item.path("value").asText());
        }
        assertTrue(values.contains("pending"), "gp_approval_status must include PENDING");
        assertTrue(values.contains("approved"), "gp_approval_status must include APPROVED");
        assertTrue(values.contains("rejected"), "gp_approval_status must include REJECTED");
    }

    // ========== Test 7: all commands have idempotent and reversible flags ==========

    @Test
    @Order(7)
    void goldenPathCommands_haveIdempotentAndReversibleFlags() throws IOException {
        String[] commandFiles = {
            "config/commands/gp_task.json",
            "config/commands/gp_approval.json",
            "config/commands/gp_task_comment.json"
        };

        for (String commandFile : commandFiles) {
            JsonNode commands = MAPPER.readTree(goldenPathDir.resolve(commandFile).toFile());
            assertTrue(commands.isArray(), commandFile + " must be a JSON array");

            for (JsonNode cmd : commands) {
                String code = cmd.path("code").asText("(unknown)");
                assertTrue(cmd.has("idempotent"),
                        "Command '" + code + "' in " + commandFile + " must have 'idempotent' flag");
                assertTrue(cmd.has("reversible"),
                        "Command '" + code + "' in " + commandFile + " must have 'reversible' flag");
            }
        }
    }
}
