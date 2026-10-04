package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CircularDependencyValidatorTest {
    private final CircularDependencyValidator validator = new CircularDependencyValidator();

    @Test
    void cycleContainsOnlyOneClosingNode() {
        assertEquals(List.of("a", "b", "a"), CircularDependencyValidator.detectCycle(
            Map.of("a", Set.of("b"), "b", Set.of("a")), "a"));
        assertEquals(List.of("a", "a"), CircularDependencyValidator.detectCycle(Map.of("a", Set.of("a")), "a"));
    }

    @Test
    void cycleOmitsNonCyclicPrefix() {
        assertEquals(List.of("a", "b", "a"), CircularDependencyValidator.detectCycle(
            Map.of("start", Set.of("a"), "a", Set.of("b"), "b", Set.of("a")), "start"));
    }

    @Test
    void diamondAndUninstalledLeafAreNotCycles() {
        assertNull(CircularDependencyValidator.detectCycle(Map.of(
            "a", new LinkedHashSet<>(List.of("b", "c")), "b", Set.of("leaf"), "c", Set.of("leaf")), "a"));
        assertEquals("governance", validator.category());
        assertTrue(validator.requiresReferenceValidation());
    }

    @Test
    void importedPluginOverridesItsInstalledDependencies() {
        var manifest = new PluginManifestExtended();
        manifest.setDependencies(List.of("b"));
        var context = PluginValidationContext.builder().pluginId("a").manifest(manifest)
            .installedPluginDependencies(Map.of("a", List.of(), "b", List.of("a"))).build();
        var messages = validator.validate(context);
        assertEquals(1, messages.size());
        assertEquals("G-CYCLE", messages.get(0).getCode());
        assertTrue(messages.get(0).isError());
        assertEquals("Circular dependency detected: a → b → a", messages.get(0).getMessage());
    }

    @Test
    void noInstalledGraphOrNoDependencyIsValid() {
        var manifest = new PluginManifestExtended();
        var context = PluginValidationContext.builder().pluginId("a").manifest(manifest).build();
        assertEquals(List.of(), validator.validate(context));
        manifest.setDependencies(List.of("external"));
        assertEquals(List.of(), validator.validate(context));
    }
}
