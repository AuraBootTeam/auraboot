package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompletenessValidatorBoundaryTest {
    private final CompletenessValidator validator = new CompletenessValidator();

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    void listPageCompletenessDoesNotDependOnHostLocale(String locale) {
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(locale));
            var manifest = manifest(); manifest.setPages(List.of(page("LIST")));
            manifest.setCommands(List.of(command("create")));
            manifest.setModelFieldBindings(List.of(binding()));
            var messages = validator.validate(context(manifest));
            assertEquals(1, messages.size());
            assertTrue(messages.get(0).getMessage().contains("no form page"));
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void nullModelEntriesDoNotSuppressValidModelChecks() {
        var manifest = manifest(); manifest.setModels(Arrays.asList(null, new ModelDefinitionDTO(), model()));
        manifest.setCommands(List.of(command("create")));
        assertMissingBindings(manifest);
    }

    @Test
    void nullPagesAndIncompletePagesDoNotSuppressValidListChecks() {
        var manifest = manifest(); manifest.setPages(Arrays.asList(null, new PageSchemaDTO(), page("list")));
        var missingKind = page(null);
        var missingModel = page("form"); missingModel.setModelCode(null);
        manifest.getPages().set(1, missingKind);
        manifest.setCommands(List.of(command("create")));
        manifest.setModelFieldBindings(List.of(binding()));
        assertEquals(1, validator.validate(context(manifest)).size());
        manifest.setPages(List.of(missingModel, page("form")));
        assertEquals(List.of(), validator.validate(context(manifest)));
    }

    @Test
    void nullCommandsAndIncompleteCommandsDoNotSuppressCreateChecks() {
        var manifest = manifest();
        var noModel = command("create"); noModel.setModelCode(null);
        manifest.setCommands(Arrays.asList(null, new CommandDefinitionDTO(), command(null), noModel, command("create")));
        assertMissingBindings(manifest);
    }

    @Test
    void nullBindingsDoNotSuppressExistingBindings() {
        var manifest = manifest(); manifest.setCommands(List.of(command("create")));
        manifest.setModelFieldBindings(Arrays.asList(null, new ModelFieldBindingDTO(), binding()));
        assertEquals(List.of(), validator.validate(context(manifest)));
    }

    @Test
    void nullInstalledFieldSetAndModelsWithoutPagesOrCommandsAreHandled() {
        var manifest = manifest();
        assertEquals(List.of(), validator.validate(context(manifest)));
        manifest.setCommands(List.of(command("create")));
        assertMissingBindings(manifest);
        manifest.setModels(List.of(new ModelDefinitionDTO()));
        assertEquals(List.of(), validator.validate(context(manifest)));
    }

    private void assertMissingBindings(PluginManifestExtended manifest) {
        var messages = validator.validate(context(manifest));
        assertEquals(1, messages.size()); assertTrue(messages.get(0).getMessage().contains("no field bindings"));
        assertTrue(messages.get(0).isWarning());
    }
    private ModelDefinitionDTO model() {
        var model = new ModelDefinitionDTO(); model.setCode("test_entity"); return model;
    }
    private PluginManifestExtended manifest() {
        var manifest = new PluginManifestExtended(); manifest.setModels(List.of(model())); return manifest;
    }
    private PageSchemaDTO page(String kind) {
        var page = new PageSchemaDTO(); page.setModelCode("test_entity"); page.setKind(kind); return page;
    }
    private CommandDefinitionDTO command(String type) {
        var command = new CommandDefinitionDTO(); command.setModelCode("test_entity"); command.setType(type); return command;
    }
    private ModelFieldBindingDTO binding() {
        var binding = new ModelFieldBindingDTO(); binding.setModelCode("test_entity"); binding.setFieldCode("name"); return binding;
    }
    private PluginValidationContext context(PluginManifestExtended manifest) {
        return PluginValidationContext.builder().manifest(manifest).build();
    }
}
