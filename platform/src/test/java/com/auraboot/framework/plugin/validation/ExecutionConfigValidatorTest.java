package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.BindingRuleDTO;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionConfigValidatorTest {
    private final ExecutionConfigValidator validator = new ExecutionConfigValidator();

    @Test
    void resultCachePolicyIsValidatedOnConsolidatedExecutionMetadata() {
        var cmd = command("query");
        var config = new CommandDefinitionDTO.ExecutionConfig();
        config.setCacheResult(false);
        cmd.setExecutionConfig(config);
        assertEquals(List.of(), validate(List.of(cmd)));
        config.setRequireIdempotencyKey(true);
        assertEquals("S-EXEC-RESULT-CACHE", validate(List.of(cmd)).get(0).getCode());
        config.setRequireIdempotencyKey(false);
        cmd.setType("update");
        assertEquals("commands[0].executionConfig.cacheResult", validate(List.of(cmd)).get(0).getPath());
        config.setCacheResult(true);
        assertEquals(List.of(), validate(List.of(cmd)));
        cmd.setType("query"); config.setCacheResult(false);
        cmd.setHandlerParams(Map.of("targetRecordAction", "update"));
        assertTrue(validate(List.of(cmd)).get(0).isError());
    }

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

    @Test
    void unsupportedVersionIntentRoundTripsOnlyItsExplicitOption() {
        var cmd = unsupported();
        assertEquals(List.of(), validate(List.of(cmd)));
        assertEquals(
                Map.of("targetVersionPolicy", "unsupported"),
                cmd.getConsolidatedExecutionConfig().get("options"));
        cmd.setUnknownField("casRequired", false);
        assertEquals(List.of(), validate(List.of(cmd)));
    }

    @Test
    void targetVersionPolicyRejectsUnknownNullAndNonStringValues() {
        for (Object value : Arrays.asList("unknown", true, 1, null)) {
            var cmd = unsupported();
            var options = new LinkedHashMap<String, Object>();
            options.put("targetVersionPolicy", value);
            cmd.getExecutionConfig().setOptions(options);
            assertPolicyError(cmd);
        }
    }

    @Test
    void unsupportedRejectsDeclaredCasEvenWhenDtoRetainsItOnlyAsAnUnknownField() {
        for (Object value : Arrays.asList(true, "false", 0, null)) {
            var cmd = unsupported();
            cmd.setUnknownField("casRequired", value);
            assertPolicyError(cmd);
        }
    }

    @Test
    void unsupportedRejectsOtherTypesMissingHandlerAndPreHandlerMutationDeclarations() {
        var wrong = unsupported();
        wrong.setType("update");
        assertPolicyError(wrong);
        wrong = unsupported();
        wrong.setHandler(" ");
        assertPolicyError(wrong);
        var mutations =
                List.<java.util.function.Consumer<CommandDefinitionDTO>>of(
                        c -> c.setAutoSetFields(Map.of()),
                        c -> c.setInputFields(List.of()),
                        c -> c.setComputedFields(Map.of()),
                        c -> c.setPreActions(List.of()),
                        c -> c.setStateField("state"),
                        c -> c.setFromStates(List.of("pending")),
                        c -> c.setToState("sent"),
                        c -> c.setSideEffects(List.of()),
                        c -> c.setPostActions(List.of()));
        for (var mutation : mutations) {
            var cmd = unsupported();
            mutation.accept(cmd);
            assertPolicyError(cmd);
        }
    }

    @Test
    void unsupportedRejectsAsyncAndDslPersistenceButAllowsExplicitSyncFalseFlags() {
        for (var params :
                List.<Map<String, Object>>of(
                        Map.of("async", true),
                        Map.of("async", "false"),
                        Map.of("dslPersistence", true))) {
            var cmd = unsupported();
            cmd.setHandlerParams(params);
            assertPolicyError(cmd);
        }
        var cmd = unsupported();
        cmd.setHandlerParams(Map.of("async", false, "dslPersistence", false));
        cmd.getExecutionConfig().setExecutionMode("SYNC");
        assertEquals(List.of(), validate(List.of(cmd)));
        cmd.getExecutionConfig().setExecutionMode("ASYNC");
        assertPolicyError(cmd);
    }

    @Test
    void unsupportedRejectsLocalAndGlobalBindingRules() {
        var cmd = unsupported();
        var rule = new BindingRuleDTO();
        rule.setCommandCode(cmd.getCode());
        rule.setRuleType("field_map");
        cmd.setBindingRules(List.of(rule));
        assertPolicyError(cmd);
        cmd.setBindingRules(null);
        var manifest = new PluginManifestExtended();
        manifest.setCommands(List.of(cmd));
        manifest.setBindingRules(List.of(rule));
        assertTrue(
                validator
                        .validate(PluginValidationContext.builder().manifest(manifest).build())
                        .stream()
                        .anyMatch(
                                m ->
                                        m.getCode().equals("S-EXEC-TARGET-VERSION-POLICY")
                                                && m.isError()));
    }

    @Test
    void currentStateImportRetainsItsPreviousStateTransitionWarnings() {
        var cmd = command("state_transition");
        var execution = new CommandDefinitionDTO.ExecutionConfig();
        execution.setOptions(Map.of("targetVersionPolicy", "current_state"));
        cmd.setExecutionConfig(execution);
        assertEquals(
                List.of("S-EXEC-ST-FIELD", "S-EXEC-ST-TO"),
                validate(List.of(cmd)).stream().map(PluginValidationMessage::getCode).toList());
    }

    private CommandDefinitionDTO unsupported() {
        var cmd = command("custom");
        cmd.setHandler("test:command");
        var execution = new CommandDefinitionDTO.ExecutionConfig();
        execution.setOptions(Map.of("targetVersionPolicy", "unsupported"));
        cmd.setExecutionConfig(execution);
        return cmd;
    }
    private void assertPolicyError(CommandDefinitionDTO cmd) {
        var errors =
                validate(List.of(cmd)).stream()
                        .filter(m -> m.getCode().equals("S-EXEC-TARGET-VERSION-POLICY"))
                        .toList();
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().isError());
        assertEquals(
                "commands[0].executionConfig.options.targetVersionPolicy",
                errors.getFirst().getPath());
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
