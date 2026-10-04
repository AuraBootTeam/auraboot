package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionConfigValidatorTest {
    private final ExecutionConfigValidator validator = new ExecutionConfigValidator();

    @Test
    void missingCommandsNullEntriesAndNoConfigurationAreAccepted() {
        assertEquals("semantic", validator.category());
        assertEquals(List.of(), validate(null));
        assertEquals(List.of(), validate(Arrays.asList(null, new CommandDefinitionDTO())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "delete", "query", "batch", "custom", "action"})
    void registeredCommandTypesAreAccepted(String type) {
        var command = command(type);
        assertEquals(List.of(), validate(List.of(command)));
    }

    @Test
    void invalidCommandTypeIsAnErrorAtItsExactArrayPath() {
        var messages = validate(Arrays.asList(null, command("CREATE")));
        assertEquals(1, messages.size());
        assertEquals("S-EXEC-TYPE", messages.get(0).getCode());
        assertEquals("commands[1].type", messages.get(0).getPath());
        assertTrue(messages.get(0).isError());
        assertTrue(messages.get(0).getMessage().contains("test:command"));
    }

    @Test
    void transitionsRequireStateFieldAndTargetOrRules() {
        var command = command("state_transition");
        assertEquals(List.of("S-EXEC-ST-FIELD", "S-EXEC-ST-TO"),
            validate(List.of(command)).stream().map(PluginValidationMessage::getCode).toList());
        command.setStateField("status");
        assertEquals(List.of("S-EXEC-ST-TO"), validate(List.of(command)).stream().map(PluginValidationMessage::getCode).toList());
        command.setToState("approved");
        assertEquals(List.of(), validate(List.of(command)));
        command.setToState(null);
        command.setStateTransitionRules(List.of());
        assertEquals(List.of(), validate(List.of(command)));
    }

    @Test
    void autoSetStrategiesReportOnlyInvalidSpecs() {
        var command = command("create");
        var specs = new LinkedHashMap<String, Map<String, Object>>();
        specs.put("owner", Map.of("strategy", "current_user"));
        specs.put("createdOn", Map.of("strategy", "current_date"));
        specs.put("unconfigured", Map.of());
        specs.put("nullSpec", null);
        specs.put("bad", Map.of("strategy", "guess"));
        command.setAutoSetFields(specs);
        var messages = validate(List.of(command));
        assertEquals(1, messages.size());
        assertEquals("S-EXEC-AUTOSET", messages.get(0).getCode());
        assertEquals("commands[0].autoSetFields.bad.strategy", messages.get(0).getPath());
        assertTrue(messages.get(0).isWarning());
    }

    @Test
    void preconditionsKeepIndexedPathsAndDoNotTurnWarningsIntoErrors() {
        var command = command("update");
        command.setPreconditions(Arrays.asList(null, Map.of(), Map.of("operator", "EQ"), Map.of("operator", "not_in"), Map.of("operator", "unknown")));
        var messages = validate(List.of(command));
        assertEquals(1, messages.size());
        assertEquals("S-EXEC-PRECOND", messages.get(0).getCode());
        assertEquals("commands[0].preconditions[4].operator", messages.get(0).getPath());
        assertTrue(messages.get(0).isWarning());
        command.setPreconditions(List.of());
        command.setAutoSetFields(Map.of());
        assertEquals(List.of(), validate(List.of(command)));
    }

    @Test
    void handlerOnlyConfigurationDoesNotRequireAType() {
        var command = command(null);
        command.setHandler("customHandler");
        assertEquals(List.of(), validate(List.of(command)));
    }

    private CommandDefinitionDTO command(String type) {
        var command = new CommandDefinitionDTO();
        command.setCode("test:command");
        command.setType(type);
        return command;
    }
    private List<PluginValidationMessage> validate(List<CommandDefinitionDTO> commands) {
        var manifest = new PluginManifestExtended();
        manifest.setCommands(commands);
        return validator.validate(PluginValidationContext.builder().manifest(manifest).build());
    }
}
