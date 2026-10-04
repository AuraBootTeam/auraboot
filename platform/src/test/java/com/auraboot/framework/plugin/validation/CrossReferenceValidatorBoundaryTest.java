package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrossReferenceValidatorBoundaryTest {
    private final CrossReferenceValidator validator = new CrossReferenceValidator();

    @Test
    void nullInstalledSetsAndIncompleteDeclaredCodesDoNotCountAsResources() {
        var manifest = new PluginManifestExtended();
        manifest.setModels(Arrays.asList(null, new ModelDefinitionDTO()));
        manifest.setFields(Arrays.asList(null, new FieldDefinitionDTO()));
        manifest.setPermissions(Arrays.asList(null, new PermissionDefinitionDTO()));
        var binding = new ModelFieldBindingDTO(); binding.setModelCode("missing_model"); binding.setFieldCode("missing_field");
        manifest.setModelFieldBindings(Arrays.asList(null, new ModelFieldBindingDTO(), binding));
        var menu = new MenuDefinitionDTO(); menu.setName("Menu name"); menu.setPermissionCode("missing_permission");
        manifest.setMenus(Arrays.asList(null, new MenuDefinitionDTO(), menu));
        var messages = validator.validate(context(manifest));
        assertEquals(List.of("S-REF-BINDING-MODEL", "S-REF-BINDING-FIELD", "S-REF-MENU-PERMISSION"),
            messages.stream().map(PluginValidationMessage::getCode).toList());
        assertTrue(messages.stream().allMatch(PluginValidationMessage::isError));
        assertTrue(messages.get(2).getMessage().contains("Menu name"));
        assertTrue(validator.requiresReferenceValidation());
    }

    @Test
    void installedFieldsAndPermissionsCanSatisfyReferencesWithoutLocalDefinitions() {
        var manifest = new PluginManifestExtended();
        var binding = new ModelFieldBindingDTO(); binding.setModelCode("installed_model"); binding.setFieldCode("installed_field");
        manifest.setModelFieldBindings(List.of(binding));
        var menu = new MenuDefinitionDTO(); menu.setPermissionCode("installed_permission"); manifest.setMenus(List.of(menu));
        var context = PluginValidationContext.builder().manifest(manifest).installedModelCodes(Set.of("installed_model"))
            .installedFieldCodes(Set.of("installed_field")).installedPermissionCodes(Set.of("installed_permission")).build();
        assertEquals(List.of(), validator.validate(context));
    }

    @Test
    void deferredModelAndPermissionReferencesNeverDeferMissingFields() {
        var manifest = new PluginManifestExtended();
        var binding = new ModelFieldBindingDTO(); binding.setModelCode("missing_model"); binding.setFieldCode("missing_field");
        manifest.setModelFieldBindings(List.of(binding));
        var context = context(manifest); context.setDeferReferenceValidation(true);
        var messages = validator.validate(context);
        assertEquals(List.of("warning", "error"), messages.stream().map(PluginValidationMessage::getSeverity).toList());
        assertEquals(List.of("modelFieldBindings[0].modelCode", "modelFieldBindings[0].fieldCode"),
            messages.stream().map(PluginValidationMessage::getPath).toList());
    }
    private PluginValidationContext context(PluginManifestExtended manifest) {
        return PluginValidationContext.builder().manifest(manifest).build();
    }
}
