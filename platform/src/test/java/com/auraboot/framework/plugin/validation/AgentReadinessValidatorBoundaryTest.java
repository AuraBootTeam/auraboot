package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentReadinessValidatorBoundaryTest {
    private final AgentReadinessValidator validator = new AgentReadinessValidator();

    @Test
    void absentExecutionConfigurationProducesAdviceRatherThanAnInternalFailure() {
        var command = command(null);
        var result = new PluginValidationPipeline(List.of(validator)).validate(context(List.of(command)));
        assertEquals(0, result.getErrorCount());
        assertEquals(List.of("A-DESC-QUALITY", "A-INPUT-SCHEMA", "A-HINT-COVERAGE"),
            validator.validate(context(List.of(command))).stream().map(PluginValidationMessage::getCode).toList());
    }

    @Test
    void nullEntriesDoNotPreventFollowingCommandsFromBeingChecked() {
        var messages = validator.validate(context(Arrays.asList(null, command("query"))));
        assertEquals(List.of("A-DESC-QUALITY", "A-HINT-COVERAGE"), messages.stream().map(PluginValidationMessage::getCode).toList());
        assertEquals("commands[1]", messages.get(0).getPath());
        assertTrue(messages.get(1).getMessage().contains("0/1 commands"));
        assertEquals(List.of(), validator.validate(context(Arrays.asList((CommandDefinitionDTO) null))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fromStates", "toState", "stateField"})
    void partialStateSpecificationsIdentifyOnlyMissingFields(String missing) {
        var command = command("state_transition");
        command.setFromStates(List.of("draft")); command.setToState("approved"); command.setStateField("status");
        switch (missing) {
            case "fromStates" -> command.setFromStates(null);
            case "toState" -> command.setToState(null);
            case "stateField" -> command.setStateField(null);
        }
        var messages = validator.validate(context(List.of(command)));
        var warning = messages.stream().filter(m -> "A-STATE-INCOMPLETE".equals(m.getCode())).findFirst().orElseThrow();
        assertTrue(warning.getMessage().contains("is missing: " + missing + "."));
        assertTrue(warning.isWarning());
    }

    @Test
    void hintAndInputFieldsSatisfyReadinessWhileBlankHintsDoNot() {
        var command = command("create");
        command.setInputFields(List.of("name")); command.setUnknownFields(Map.of("agentHint", "Create a customer"));
        assertEquals(List.of(), validator.validate(context(List.of(command))));
        command.setUnknownFields(Map.of("agentHint", " ")); command.setInputFields(List.of());
        assertEquals(List.of("A-DESC-QUALITY", "A-INPUT-SCHEMA", "A-HINT-COVERAGE"),
            validator.validate(context(List.of(command))).stream().map(PluginValidationMessage::getCode).toList());
        command.setUnknownFields(Map.of("agentHint", 1));
        assertTrue(validator.validate(context(List.of(command))).stream().anyMatch(m -> "A-DESC-QUALITY".equals(m.getCode())));
    }

    private CommandDefinitionDTO command(String type) {
        var command = new CommandDefinitionDTO(); command.setCode("test:command"); command.setType(type); return command;
    }
    private PluginValidationContext context(List<CommandDefinitionDTO> commands) {
        var manifest = new PluginManifestExtended(); manifest.setCommands(commands);
        return PluginValidationContext.builder().manifest(manifest).build();
    }
}
