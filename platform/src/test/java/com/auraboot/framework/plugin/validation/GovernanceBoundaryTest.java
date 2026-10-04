package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GovernanceBoundaryTest {
    @Test
    void i18nCoverageSkipsIncompleteResourcesAndReportsOnlyUntranslatedKeys() {
        var manifest = new PluginManifestExtended();
        var validator = new I18nCoverageValidator();
        assertEquals("governance", validator.category());
        assertEquals(List.of(), validator.validate(context(manifest)));
        var missingModel = new ModelDefinitionDTO(); missingModel.setCode("test_missing");
        var coveredModel = new ModelDefinitionDTO(); coveredModel.setCode("test_covered");
        manifest.setModels(Arrays.asList(null, new ModelDefinitionDTO(), missingModel, coveredModel));
        var missingField = binding("test_missing", "name");
        manifest.setModelFieldBindings(Arrays.asList(null, binding(null, "name"), binding("test_missing", null), missingField, binding("test_covered", "name")));
        var untranslated = new I18nDefinitionDTO(); untranslated.setKey("model.test_missing._meta.label");
        var modelLabel = new I18nDefinitionDTO(); modelLabel.setKey("model.test_covered._meta.label"); modelLabel.setJaJP("Covered model");
        var fieldLabel = new I18nDefinitionDTO(); fieldLabel.setKey("model.test_covered.name.label"); fieldLabel.setExtra("fr-FR", "Covered field");
        manifest.setI18nResources(Arrays.asList(null, new I18nDefinitionDTO(), untranslated, modelLabel, fieldLabel));
        var messages = validator.validate(context(manifest));
        assertEquals(List.of("G-I18N-MODEL", "G-I18N-FIELD"), messages.stream().map(PluginValidationMessage::getCode).toList());
        assertEquals(List.of("info", "info"), messages.stream().map(PluginValidationMessage::getSeverity).toList());
        assertTrue(messages.get(0).getMessage().contains("model.test_missing._meta.label"));
        assertTrue(messages.get(1).getMessage().contains("model.test_missing.name.label"));
    }

    @Test
    void namespaceValidationSkipsAbsentNamespaceAndIncompleteResources() {
        var validator = new NamespaceConsistencyValidator();
        var manifest = new PluginManifestExtended();
        assertEquals(List.of(), validator.validate(context(manifest)));
        assertEquals(List.of(), validator.validate(PluginValidationContext.builder().manifest(manifest).namespace("  ").build()));
        assertEquals(List.of(), validator.validate(PluginValidationContext.builder().manifest(manifest).namespace("test").build()));
        var model = new ModelDefinitionDTO(); model.setCode("wrong_model"); model.setTableName(" ");
        var bound = new ModelDefinitionDTO(); bound.setCode("external"); bound.setTableName("existing_table");
        var correct = new ModelDefinitionDTO(); correct.setCode("test_correct");
        manifest.setModels(Arrays.asList(null, new ModelDefinitionDTO(), model, bound, correct));
        var command = new CommandDefinitionDTO(); command.setCode("wrong:command");
        var correctCommand = new CommandDefinitionDTO(); correctCommand.setCode("test:correct");
        manifest.setCommands(Arrays.asList(null, new CommandDefinitionDTO(), command, correctCommand));
        var messages = validator.validate(PluginValidationContext.builder().manifest(manifest).namespace("test").build());
        assertEquals(List.of("S-NS-MODEL", "S-NS-COMMAND"), messages.stream().map(PluginValidationMessage::getCode).toList());
        assertEquals(List.of("models[2].code", "commands[2].code"), messages.stream().map(PluginValidationMessage::getPath).toList());
        assertTrue(messages.stream().allMatch(PluginValidationMessage::isWarning));
    }

    private PluginValidationContext context(PluginManifestExtended manifest) {
        return PluginValidationContext.builder().manifest(manifest).build();
    }
    private ModelFieldBindingDTO binding(String model, String field) {
        var binding = new ModelFieldBindingDTO(); binding.setModelCode(model); binding.setFieldCode(field); return binding;
    }
}
