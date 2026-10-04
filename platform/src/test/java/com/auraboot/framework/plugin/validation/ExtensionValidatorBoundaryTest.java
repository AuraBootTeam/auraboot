package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.registry.*;
import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionValidatorBoundaryTest {
    private final CommandHandlerRegistry commands = new CommandHandlerRegistry();
    private final SideEffectHandlerRegistry effects = new SideEffectHandlerRegistry();
    private final RenderComponentRegistry renderers = new RenderComponentRegistry();
    private final ExtensionValidator validator = new ExtensionValidator(commands, effects, renderers);

    @Test
    void declaredHandlersAreCheckedAgainstActualRegistries() {
        var manifest = new PluginManifestExtended();
        var valid = command("registered"); var invalid = command("missing");
        manifest.setCommands(Arrays.asList(null, new CommandDefinitionDTO(), command(" "), valid, invalid));
        commands.register(new CommandHandlerRegistry.HandlerMeta("registered", "test", "Handler", null, null));
        var messages = validate(manifest);
        assertEquals(List.of("S-EXT-HANDLER"), codes(messages));
        assertEquals("commands[4].handler", messages.get(0).getPath());
        assertTrue(messages.get(0).isError());
        assertTrue(validator.requiresReferenceValidation());
        assertEquals("semantic", validator.category());
        valid.setBindingRules(List.of());
        assertEquals(1, validate(manifest).size());
    }

    @Test
    void topLevelAndNestedSideEffectsHaveIndexedErrorsAndCanUseRegisteredHandlers() {
        effects.register(new SideEffectHandlerRegistry.HandlerMeta("registered", "test", "Effect"));
        var command = command(null);
        var first = effect("missing"); first.setActions(Arrays.asList(null, Map.of(), Map.of("action", " "),
            Map.of("action", "registered"), Map.of("action", "nested_missing")));
        var second = effect("registered"); second.setActions(List.of());
        command.setSideEffects(Arrays.asList(null, effect(null), effect(" "), first, second));
        var manifest = new PluginManifestExtended(); manifest.setCommands(List.of(command));
        var messages = validate(manifest);
        assertEquals(List.of("S-EXT-SIDEEFFECT", "S-EXT-SIDEEFFECT"), codes(messages));
        assertEquals(List.of("commands[0].sideEffects[3].action", "commands[0].sideEffects[3].actions[4].action"),
            messages.stream().map(PluginValidationMessage::getPath).toList());
        assertTrue(messages.stream().allMatch(PluginValidationMessage::isError));
    }

    @ParameterizedTest
    @CsvSource({"string, SmartInput", "text, SmartTextArea", "integer, SmartNumber", "decimal, SmartNumber",
        "boolean, SmartSwitch", "date, SmartDatePicker", "datetime, SmartDateTimePicker", "enum, SmartSelect",
        "ref, SmartLookup", "file, SmartUpload", "image, SmartImageUpload"})
    void supportedTypeRendererPairsAreAccepted(String type, String component) {
        register(component);
        var manifest = new PluginManifestExtended(); manifest.setFields(List.of(field(type, component)));
        assertEquals(List.of(), validate(manifest));
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    void incompatibleRendererCannotEscapeValidationByChangingLocale(String locale) {
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(locale));
            register("SmartNumber");
            var manifest = new PluginManifestExtended(); manifest.setFields(List.of(field("STRING", "SmartNumber")));
            var messages = validate(manifest);
            assertEquals(List.of("S-EXT-TYPE-COMPAT"), codes(messages));
            assertTrue(messages.get(0).isWarning());
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void missingExtensionMetadataAndUnknownTypesDoNotInventCompatibilityFailures() {
        register("SmartInput");
        var noComponent = new FieldDefinitionDTO(); noComponent.setExtension(Map.of());
        var manifest = new PluginManifestExtended();
        manifest.setFields(Arrays.asList(null, new FieldDefinitionDTO(), noComponent,
            field(null, "SmartInput"), field(" ", "SmartInput"), field("custom_type", "SmartInput"), field("string", " ")));
        assertEquals(List.of(), validate(manifest));
        assertEquals(List.of(), validate(new PluginManifestExtended()));
    }

    @Test
    void frontendProvidedRendererIsAnAdvisoryFindingWithTheOriginalFieldPath() {
        var manifest = new PluginManifestExtended(); manifest.setFields(List.of(field("string", "PluginInput")));
        var messages = validate(manifest);
        assertEquals(List.of("S-EXT-RENDER", "S-EXT-TYPE-COMPAT"), codes(messages));
        assertTrue(messages.stream().allMatch(PluginValidationMessage::isWarning));
        assertTrue(messages.stream().allMatch(m -> "fields[0].extension.renderComponent".equals(m.getPath())));
    }

    private void register(String code) { renderers.register(new RenderComponentRegistry.ComponentMeta(code, "test", List.of(), "input")); }
    private CommandDefinitionDTO command(String handler) { var command = new CommandDefinitionDTO(); command.setCode("test:command"); command.setHandler(handler); return command; }
    private CommandDefinitionDTO.SideEffectConfig effect(String action) { var effect = new CommandDefinitionDTO.SideEffectConfig(); effect.setAction(action); return effect; }
    private FieldDefinitionDTO field(String type, String component) {
        var field = new FieldDefinitionDTO(); field.setCode("test_field"); field.setDataType(type); field.setExtension(Map.of("renderComponent", component)); return field;
    }
    private List<PluginValidationMessage> validate(PluginManifestExtended manifest) { return validator.validate(PluginValidationContext.builder().manifest(manifest).build()); }
    private List<String> codes(List<PluginValidationMessage> messages) { return messages.stream().map(PluginValidationMessage::getCode).toList(); }
}
