package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolbarActionValidator.
 * No Spring context — validator is a pure function.
 */
class ToolbarActionValidatorTest {

    private ToolbarActionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ToolbarActionValidator();
    }

    // ==================== SEM-TB-001: CREATE + type=command → ERROR ====================

    @Test
    void validate_createCommandWithTypeCommand_returnsError() {
        PluginManifestExtended manifest = buildManifest(
                List.of(command("ns:create_item", "create")),
                List.of(pageWithToolbarButton("item_list", "item_list", "create_btn",
                        actionCommand("ns:create_item")))
        );
        var messages = validator.validate(ctx(manifest));

        assertFalse(messages.isEmpty(), "Expected error for CREATE + type=command");
        var error = messages.stream().filter(PluginValidationMessage::isError).findFirst().orElse(null);
        assertNotNull(error, "Expected an ERROR-level message");
        assertEquals("SEM-TB-001", error.getCode());
        assertTrue(error.getMessage().contains("create_btn"));
        assertTrue(error.getMessage().contains("item_list"));
        assertTrue(error.getMessage().contains("ns:create_item"));
        assertTrue(error.getMessage().contains("type='navigate'"));
    }

    @Test
    void validate_createCommandInferredFromCodePattern_returnsError() {
        // No explicit type field — inferred from "create_" prefix in command code
        PluginManifestExtended manifest = buildManifest(
                List.of(command("ns:create_order", null)),  // no explicit type
                List.of(pageWithToolbarButton("order_list", "order_list", "btn_new",
                        actionCommand("ns:create_order")))
        );
        var messages = validator.validate(ctx(manifest));

        assertTrue(messages.stream().anyMatch(m -> "SEM-TB-001".equals(m.getCode())),
                "Should detect CREATE by name pattern even without explicit type field");
    }

    @Test
    void validate_createCommandNotInPlugin_inferredFromCodePattern_returnsError() {
        // Command not in plugin commands list — still inferred from name pattern
        PluginManifestExtended manifest = buildManifest(
                List.of(), // empty commands list
                List.of(pageWithToolbarButton("order_list", "order_list", "btn_new",
                        actionCommand("other:create_order")))
        );
        var messages = validator.validate(ctx(manifest));

        assertTrue(messages.stream().anyMatch(m -> "SEM-TB-001".equals(m.getCode())),
                "Should infer CREATE from command code pattern even when command not in plugin");
    }

    // ==================== SEM-TB-002: UPDATE + type=command → ERROR ====================

    @Test
    void validate_updateCommandWithTypeCommand_returnsError() {
        PluginManifestExtended manifest = buildManifest(
                List.of(command("ns:update_item", "update")),
                List.of(pageWithToolbarButton("item_list", "item_list", "edit_btn",
                        actionCommand("ns:update_item")))
        );
        var messages = validator.validate(ctx(manifest));

        assertFalse(messages.isEmpty(), "Expected error for UPDATE + type=command");
        var err = messages.stream().filter(PluginValidationMessage::isError).findFirst().orElse(null);
        assertNotNull(err);
        assertEquals("SEM-TB-002", err.getCode());
        assertTrue(err.getMessage().contains("edit_btn"));
        assertTrue(err.getMessage().contains("ns:update_item"));
    }

    // ==================== SEM-TB-003: type=navigate + missing 'to' → ERROR ====================

    @Test
    void validate_navigateWithoutTo_returnsError() {
        Map<String, Object> action = Map.of("type", "navigate"); // no "to"
        PluginManifestExtended manifest = buildManifest(
                List.of(),
                List.of(pageWithToolbarButton("my_list", "my_list", "create_btn", action))
        );
        var messages = validator.validate(ctx(manifest));

        assertTrue(messages.stream().anyMatch(m -> "SEM-TB-003".equals(m.getCode())),
                "Should report ERROR for navigate without 'to'");
        var error = messages.stream().filter(m -> "SEM-TB-003".equals(m.getCode())).findFirst().get();
        assertTrue(error.isError());
        assertTrue(error.getMessage().contains("create_btn"));
        assertTrue(error.getMessage().contains("my_list"));
        assertTrue(error.getMessage().contains("missing 'to' field"));
    }

    @Test
    void validate_navigateWithEmptyTo_returnsError() {
        Map<String, Object> action = Map.of("type", "navigate", "to", "");
        PluginManifestExtended manifest = buildManifest(
                List.of(),
                List.of(pageWithToolbarButton("my_list", "my_list", "create_btn", action))
        );
        var messages = validator.validate(ctx(manifest));

        assertTrue(messages.stream().anyMatch(m -> "SEM-TB-003".equals(m.getCode())),
                "Empty 'to' should also trigger SEM-TB-003");
    }

    // ==================== SEM-TB-004: navigate to page not in plugin → ERROR ====================

    @Test
    void validate_navigateToNonExistentPage_returnsError() {
        Map<String, Object> action = Map.of("type", "navigate", "to", "unknown_form_page");
        PluginManifestExtended manifest = buildManifest(
                List.of(),
                List.of(pageWithToolbarButton("my_list", "my_list", "create_btn", action))
        );
        var messages = validator.validate(ctx(manifest));

        assertTrue(messages.stream().anyMatch(m -> "SEM-TB-004".equals(m.getCode())),
                "Should error when navigate target is not defined in this plugin");
        var err = messages.stream().filter(m -> "SEM-TB-004".equals(m.getCode())).findFirst().get();
        assertTrue(err.isError());
        assertTrue(err.getMessage().contains("unknown_form_page"));
    }

    @Test
    void validate_navigateToExistingPage_noWarning() {
        Map<String, Object> action = Map.of("type", "navigate", "to", "item_form");
        PageSchemaDTO listPage = pageWithToolbarButton("item_list", "item_list", "create_btn", action);
        PageSchemaDTO formPage = emptyPage("item_form");

        PluginManifestExtended manifest = buildManifest(List.of(), List.of(listPage, formPage));
        var messages = validator.validate(ctx(manifest));

        assertFalse(messages.stream().anyMatch(m -> "SEM-TB-004".equals(m.getCode())),
                "Should not warn when navigate target page exists in this plugin");
    }

    @Test
    void validate_navigateToAbsolutePlatformRoute_noError() {
        // Absolute paths (starting with '/') are platform routes — exempt from SEM-TB-004
        Map<String, Object> action = Map.of("type", "navigate", "to", "/bpmn-designer");
        PluginManifestExtended manifest = buildManifest(
                List.of(),
                List.of(pageWithToolbarButton("bpm_list", "bpm_list", "create_btn", action))
        );
        var messages = validator.validate(ctx(manifest));

        assertFalse(messages.stream().anyMatch(m -> "SEM-TB-004".equals(m.getCode())),
                "Absolute platform routes (starting with '/') should not trigger SEM-TB-004");
    }

    @Test
    void validate_navigateToAbsoluteRouteWithParams_noError() {
        // Absolute paths with query params or path params
        Map<String, Object> action = Map.of("type", "navigate", "to", "/dashboard-designer/{pid}");
        PluginManifestExtended manifest = buildManifest(
                List.of(),
                List.of(pageWithToolbarButton("dash_list", "dash_list", "edit_btn", action))
        );
        var messages = validator.validate(ctx(manifest));

        assertFalse(messages.stream().anyMatch(m -> "SEM-TB-004".equals(m.getCode())),
                "Absolute platform routes with path params should not trigger SEM-TB-004");
    }

    // ==================== SEM-TB-005: DELETE without confirmation → ERROR ====================

    @Test
    void validate_deleteCommandWithoutConfirm_returnsError() {
        Map<String, Object> action = actionCommand("ns:delete_item");
        PluginManifestExtended manifest = buildManifest(
                List.of(command("ns:delete_item", "delete")),
                List.of(pageWithToolbarButton("item_list", "item_list", "delete_btn", action))
        );
        var messages = validator.validate(ctx(manifest));

        assertTrue(messages.stream().anyMatch(m -> "SEM-TB-005".equals(m.getCode())),
                "Should error when DELETE has no confirmation");
        var err = messages.stream().filter(m -> "SEM-TB-005".equals(m.getCode())).findFirst().get();
        assertTrue(err.isError());
        assertTrue(err.getMessage().contains("delete_btn"));
        assertTrue(err.getMessage().contains("ns:delete_item"));
    }

    @Test
    void validate_deleteCommandWithConfirm_noWarning() {
        Map<String, Object> action = actionCommand("ns:delete_item");
        // Build button with confirm field
        Map<String, Object> button = mapOf("code", "delete_btn", "action", action, "confirm", "delete.confirm");
        PageSchemaDTO page = pageWithToolbarButtons("item_list", "item_list", List.of(button));

        PluginManifestExtended manifest = buildManifest(
                List.of(command("ns:delete_item", "delete")),
                List.of(page)
        );
        var messages = validator.validate(ctx(manifest));

        assertFalse(messages.stream().anyMatch(m -> "SEM-TB-005".equals(m.getCode())),
                "Should not warn for DELETE with confirmation configured");
    }

    // ==================== Happy path: correct navigate configuration → no errors ====================

    @Test
    void validate_correctNavigateAction_noErrors() {
        Map<String, Object> action = Map.of("type", "navigate", "to", "item_form", "command", "ns:create_item");
        PageSchemaDTO listPage = pageWithToolbarButton("item_list", "item_list", "create_btn", action);
        PageSchemaDTO formPage = emptyPage("item_form");

        PluginManifestExtended manifest = buildManifest(
                List.of(command("ns:create_item", "create")),
                List.of(listPage, formPage)
        );
        var messages = validator.validate(ctx(manifest));

        // No SEM-TB-001 since action type is navigate, not command
        assertFalse(messages.stream().anyMatch(m -> m.getCode().startsWith("SEM-TB-00")),
                "Correct navigate action with matching form page should produce no toolbar errors");
    }

    @Test
    void validate_stateTransitionCommandWithTypeCommand_noCreateOrUpdateError() {
        // State transitions are fine to execute directly (no form needed)
        PluginManifestExtended manifest = buildManifest(
                List.of(command("ns:approve_item", "state_transition")),
                List.of(pageWithToolbarButton("item_list", "item_list", "approve_btn",
                        actionCommand("ns:approve_item")))
        );
        var messages = validator.validate(ctx(manifest));

        assertFalse(messages.stream().anyMatch(m -> "SEM-TB-001".equals(m.getCode())),
                "State transition should not trigger SEM-TB-001");
        assertFalse(messages.stream().anyMatch(m -> "SEM-TB-002".equals(m.getCode())),
                "State transition should not trigger SEM-TB-002");
    }

    @Test
    void validate_nullManifest_returnsEmpty() {
        var ctx = PluginValidationContext.builder().build();
        var messages = validator.validate(ctx);
        assertTrue(messages.isEmpty());
    }

    @Test
    void validate_noPages_returnsEmpty() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        var messages = validator.validate(ctx(manifest));
        assertTrue(messages.isEmpty());
    }

    @Test
    void validate_pageWithNoDslSchema_returnsEmpty() {
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("item_list");
        page.setKind("list");
        PluginManifestExtended manifest = buildManifest(List.of(), List.of(page));
        var messages = validator.validate(ctx(manifest));
        assertTrue(messages.isEmpty());
    }

    @Test
    void validate_pageWithNoToolbarArea_returnsEmpty() {
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("item_list");
        page.setKind("list");
        page.setBlocks(List.of(Map.of("blockType", "other")));
        PluginManifestExtended manifest = buildManifest(List.of(), List.of(page));
        var messages = validator.validate(ctx(manifest));
        assertTrue(messages.isEmpty());
    }

    @Test
    void category_returnsSemantic() {
        assertEquals("semantic", validator.category());
    }

    // ==================== Helpers ====================

    private PluginValidationContext ctx(PluginManifestExtended manifest) {
        return PluginValidationContext.builder()
                .pluginId("test-plugin")
                .namespace("ns")
                .manifest(manifest)
                .installedModelCodes(Set.of())
                .installedFieldCodes(Set.of())
                .installedCommandCodes(Set.of())
                .build();
    }

    private PluginManifestExtended buildManifest(List<CommandDefinitionDTO> commands, List<PageSchemaDTO> pages) {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setCommands(commands);
        manifest.setPages(pages);
        return manifest;
    }

    private CommandDefinitionDTO command(String code, String type) {
        CommandDefinitionDTO cmd = new CommandDefinitionDTO();
        cmd.setCode(code);
        cmd.setModelCode("ns_model");
        cmd.setType(type);
        return cmd;
    }

    /** Build a page DSL with a single toolbar button. */
    private PageSchemaDTO pageWithToolbarButton(String pageKey, String pageKeyActual,
                                                String buttonCode, Map<String, Object> action) {
        Map<String, Object> button = mapOf("code", buttonCode, "action", action);
        return pageWithToolbarButtons(pageKey, pageKeyActual, List.of(button));
    }

    /** Build a page DSL with multiple toolbar buttons. */
    private PageSchemaDTO pageWithToolbarButtons(String pageKey, String pageKeyActual,
                                                  List<Map<String, Object>> buttons) {
        Map<String, Object> block = mapOf("id", pageKey + "_toolbar", "blockType", "toolbar",
                "buttons", buttons);

        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey(pageKeyActual);
        page.setKind("list");
        page.setBlocks(List.of(block));
        return page;
    }

    /** Build a page DSL with no toolbar (used as a navigate target). */
    private PageSchemaDTO emptyPage(String pageKey) {
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey(pageKey);
        page.setKind("form");
        page.setBlocks(List.of());
        return page;
    }

    /** type=command action */
    private Map<String, Object> actionCommand(String commandCode) {
        return mapOf("type", "command", "command", commandCode);
    }

    /** Convenience: build a mutable map from alternating key/value pairs. */
    @SuppressWarnings("unchecked")
    private <V> Map<String, V> mapOf(Object... pairs) {
        java.util.HashMap<String, V> map = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length - 1; i += 2) {
            map.put(pairs[i].toString(), (V) pairs[i + 1]);
        }
        return map;
    }
}
