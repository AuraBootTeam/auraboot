package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import static org.junit.jupiter.api.Assertions.*;

class ToolbarActionValidatorBoundaryTest {
    private final ToolbarActionValidator validator = new ToolbarActionValidator();

    @Test
    void nullPagesDoNotSuppressFollowingToolbarChecks() {
        var manifest = new PluginManifestExtended(); manifest.setPages(Arrays.asList(null, page(button(Map.of("type", "navigate")))));
        assertCodes(manifest, "SEM-TB-003");
    }

    @Test
    void nullCommandsDoNotSuppressFollowingToolbarChecks() {
        var command = new CommandDefinitionDTO(); command.setCode("test:arbitrary"); command.setType("update");
        var manifest = manifest(button(Map.of("type", "command", "command", "test:arbitrary")));
        manifest.setCommands(Arrays.asList(null, new CommandDefinitionDTO(), command));
        assertCodes(manifest, "SEM-TB-002");
    }

    @ParameterizedTest
    @MethodSource("confirmations")
    void deleteConfirmationMustBePresentAndMeaningful(Object confirm, boolean valid) {
        var button = button(Map.of("type", "command", "command", "delete")); button.put("confirm", confirm);
        assertCodes(manifest(button), valid ? new String[0] : new String[]{"SEM-TB-005"});
    }
    static Stream<Arguments> confirmations() {
        return Stream.of(Arguments.of(null, false), Arguments.of("", false), Arguments.of(" ", false), Arguments.of("Delete record?", true),
            Arguments.of(false, false), Arguments.of(true, true), Arguments.of(Map.of(), false), Arguments.of(Map.of("message", "Delete?"), true),
            Arguments.of(1, false), Arguments.of(List.of("Delete?"), false));
    }

    @ParameterizedTest
    @MethodSource("inferredCommands")
    void crudNamesAreInferredWhileExplicitTypesTakePriority(String code, String type, String expected) {
        var manifest = manifest(button(Map.of("type", "command", "command", code)));
        var command = new CommandDefinitionDTO(); command.setCode(code); command.setType(type);
        command.setExecutionConfig(new CommandDefinitionDTO.ExecutionConfig());
        manifest.setCommands(List.of(command));
        assertCodes(manifest, expected == null ? new String[0] : new String[]{expected});
    }
    static Stream<Arguments> inferredCommands() {
        return Stream.of(Arguments.of("create", null, "SEM-TB-001"), Arguments.of("test:create_item", " ", "SEM-TB-001"),
            Arguments.of("update", null, "SEM-TB-002"), Arguments.of("test:update_item", null, "SEM-TB-002"),
            Arguments.of("delete", null, "SEM-TB-005"), Arguments.of("test:delete_item", null, "SEM-TB-005"),
            Arguments.of("test:approve", null, null), Arguments.of("test:create_item", "query", null),
            Arguments.of("test:arbitrary", "UPDATE", "SEM-TB-002"));
    }

    @ParameterizedTest
    @MethodSource("navigation")
    void navigationChecksMissingTargetsAndRespectsPlatformRoutes(Object target, String expected) {
        var action = new HashMap<String, Object>(); action.put("type", "navigate"); action.put("to", target);
        assertCodes(manifest(button(action)), expected == null ? new String[0] : new String[]{expected});
    }
    static Stream<Arguments> navigation() {
        return Stream.of(Arguments.of(null, "SEM-TB-003"), Arguments.of("", "SEM-TB-003"), Arguments.of(" ", "SEM-TB-003"),
            Arguments.of(1, "SEM-TB-003"), Arguments.of("absent", "SEM-TB-004"), Arguments.of("test_page", null), Arguments.of("/platform/route", null));
    }

    @Test
    void incompleteActionsAndNonToolbarBlocksAreOutsideThisSemanticCheck() {
        var page = new PageSchemaDTO(); page.setBlocks(Arrays.asList(null, "invalid", Map.of("blockType", "table"),
            Map.of("blockType", "toolbar"), Map.of("blockType", "toolbar", "buttons", 1),
            Map.of("blockType", "toolbar", "buttons", Arrays.asList(null, 1, Map.of(), Map.of("action", 1),
                button(Map.of()), button(Map.of("type", 1)), button(Map.of("type", "command")), button(Map.of("type", "custom"))))));
        var manifest = new PluginManifestExtended(); manifest.setPages(List.of(page));
        assertCodes(manifest);
        assertCodes(new PluginManifestExtended());
        assertEquals(List.of(), validator.validate(PluginValidationContext.builder().build()));
    }

    @Test
    void pagesWithoutKeysDoNotInventCrossPageReferenceEvidence() {
        var manifest = manifest(button(Map.of("type", "navigate", "to", "external_page")));
        manifest.getPages().get(0).setPageKey(null);
        assertCodes(manifest);
    }

    private void assertCodes(PluginManifestExtended manifest, String... expected) {
        var messages = validator.validate(PluginValidationContext.builder().manifest(manifest).build());
        assertEquals(List.of(expected), messages.stream().map(PluginValidationMessage::getCode).toList());
        assertTrue(messages.stream().allMatch(PluginValidationMessage::isError));
        if (!messages.isEmpty()) assertEquals("pages[" + (manifest.getPages().get(0) == null ? 1 : 0) + "].blocks[0].buttons[0]", messages.get(0).getPath());
    }
    private PluginManifestExtended manifest(Map<String, Object> button) {
        var manifest = new PluginManifestExtended(); manifest.setPages(List.of(page(button))); return manifest;
    }
    private PageSchemaDTO page(Map<String, Object> button) {
        var page = new PageSchemaDTO(); page.setPageKey("test_page"); page.setBlocks(List.of(Map.of("blockType", "toolbar", "buttons", List.of(button)))); return page;
    }
    private Map<String, Object> button(Map<String, Object> action) {
        var button = new HashMap<String, Object>(); button.put("action", action); return button;
    }
}
