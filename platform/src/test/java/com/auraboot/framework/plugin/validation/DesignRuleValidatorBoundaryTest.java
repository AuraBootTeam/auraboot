package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DesignRuleValidatorBoundaryTest {
    private final DesignRuleValidator validator = new DesignRuleValidator();

    @Test
    void absentCommandMetadataDoesNotTurnAdvisoryValidationIntoAnInternalError() {
        var manifest = new PluginManifestExtended(); manifest.setCommands(List.of(command(null)));
        var result = new PluginValidationPipeline(List.of(validator)).validate(context(manifest));
        assertEquals(0, result.getErrorCount());
        assertEquals(List.of(), validator.validate(context(manifest)));
    }

    @Test
    void nullCommandsDoNotPreventLaterRiskWarnings() {
        var manifest = new PluginManifestExtended(); manifest.setCommands(Arrays.asList(null, command("bulk_delete")));
        var messages = validator.validate(context(manifest));
        assertEquals(List.of("D-RISK-DELETE"), messages.stream().map(PluginValidationMessage::getCode).toList());
        assertEquals("commands[1]", messages.get(0).getPath());
    }

    @Test
    void nullModelsDoNotPreventValidEntityCoverageChecks() {
        var manifest = new PluginManifestExtended(); manifest.setModels(Arrays.asList(null, entity()));
        var messages = validator.validate(context(manifest));
        assertEquals(List.of("D-PAGE-LIST", "D-PAGE-FORM"), messages.stream().map(PluginValidationMessage::getCode).toList());
    }

    @Test
    void nullPagesAndPagesWithoutModelDoNotPreventCoverageChecks() {
        var manifest = new PluginManifestExtended(); manifest.setModels(List.of(entity()));
        var list = new PageSchemaDTO(); list.setModelCode("test_entity"); list.setKind("list");
        var unrelated = new PageSchemaDTO(); unrelated.setModelCode("test_entity"); unrelated.setKind("workbench");
        manifest.setPages(Arrays.asList(null, new PageSchemaDTO(), list, unrelated));
        assertEquals(List.of("D-PAGE-FORM"), validator.validate(context(manifest)).stream().map(PluginValidationMessage::getCode).toList());
    }

    @Test
    void sideEffectDescriptionsAndLongDescriptionsAreAcceptedButBlankHintsAreNot() {
        var command = command("create"); command.setSideEffects(List.of(new CommandDefinitionDTO.SideEffectConfig()));
        var manifest = new PluginManifestExtended(); manifest.setCommands(List.of(command));
        command.setUnknownFields(Map.of("agentHint", " ", "sideEffectDescription", "Notify stakeholders"));
        assertEquals(List.of(), validator.validate(context(manifest)));
        command.setUnknownFields(Map.of("agentHint", 1, "sideEffectDescription", " "));
        assertEquals(List.of("D-SIDE-EFFECT-DESC"), validator.validate(context(manifest)).stream().map(PluginValidationMessage::getCode).toList());
        command.setDescription("A sufficiently long description of the side effect");
        assertEquals(List.of(), validator.validate(context(manifest)));
        command.setDescription(null); command.setSideEffects(List.of());
        assertEquals(List.of(), validator.validate(context(manifest)));
    }

    private ModelDefinitionDTO entity() {
        var model = new ModelDefinitionDTO(); model.setCode("test_entity"); model.setModelType("entity"); return model;
    }
    private CommandDefinitionDTO command(String type) {
        var command = new CommandDefinitionDTO(); command.setCode("test:command"); command.setType(type); return command;
    }
    private PluginValidationContext context(PluginManifestExtended manifest) {
        return PluginValidationContext.builder().manifest(manifest).build();
    }
}
