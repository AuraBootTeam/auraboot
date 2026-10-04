package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.PluginManifest.CapabilityRequirement;
import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityDependencyValidatorBoundaryTest {
    private final CapabilityDependencyValidator validator = new CapabilityDependencyValidator();

    @Test
    void emptyRequirementsAndNullInstalledSetsAreSupported() {
        var manifest = new PluginManifestExtended(); manifest.setRequires(List.of());
        assertEquals(List.of(), validator.validate(context(manifest)));
        assertTrue(validator.requiresReferenceValidation());
        manifest.setRequires(List.of(new CapabilityRequirement("model", "absent", false)));
        manifest.setModels(Arrays.asList(null, new ModelDefinitionDTO()));
        manifest.setCommands(Arrays.asList(null, new CommandDefinitionDTO()));
        manifest.setNamedQueries(Arrays.asList(null, new NamedQueryDefinitionDTO()));
        var messages = validator.validate(context(manifest));
        assertEquals(1, messages.size());
        assertEquals("S-CAP-MISSING", messages.get(0).getCode());
        assertEquals("requires[0]", messages.get(0).getPath());
        assertTrue(messages.get(0).isError());
    }

    @Test
    void installedCommandsAndQueriesSatisfyRequirementsAndMissingOnesKeepTheirSeverity() {
        var manifest = new PluginManifestExtended();
        manifest.setRequires(List.of(new CapabilityRequirement("command", "installed:command", false),
            new CapabilityRequirement("query", "installed_query", false),
            new CapabilityRequirement("command", "missing:command", true),
            new CapabilityRequirement("query", "missing_query", false)));
        var context = PluginValidationContext.builder().manifest(manifest)
            .installedCommandCodes(Set.of("installed:command")).installedNamedQueryCodes(Set.of("installed_query")).build();
        var messages = validator.validate(context);
        assertEquals(List.of("S-CAP-OPTIONAL", "S-CAP-MISSING"), messages.stream().map(PluginValidationMessage::getCode).toList());
        assertEquals(List.of("requires[2]", "requires[3]"), messages.stream().map(PluginValidationMessage::getPath).toList());
        assertTrue(messages.get(0).isWarning()); assertTrue(messages.get(1).isError());
    }
    private PluginValidationContext context(PluginManifestExtended manifest) {
        return PluginValidationContext.builder().manifest(manifest).build();
    }
}
