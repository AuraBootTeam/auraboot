package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.service.impl.PluginDirectoryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DefinitionHandlerDependenciesTest {
    @TempDir Path root;
    private com.auraboot.framework.plugin.dto.imports.PluginManifestExtended load(String commands) throws Exception {
        Files.writeString(root.resolve("plugin.json"), """
            {"pluginId":"test.release", "version":"1.0.0", "resourceDirs":{"commands":"commands.json"}}
            """);
        Files.writeString(root.resolve("commands.json"), commands);
        return new PluginDirectoryLoader().loadFromDirectory(root);
    }
    @Test void usesActualLoaderAndConsolidatedHandlerPrecedence() throws Exception {
        var manifest = load("""
            [{"code":"edu:submit","type":"update","executionConfig":{"handler":"edu:old"},"handler":" edu:approve "},
             {"code":"edu:notify","executionConfig":{"handler":"edu:send"}}]
            """);
        var audit = DefinitionHandlerDependencies.inspect(manifest, Set.of("edu:approve", "edu:send"));
        assertEquals(List.of(new DefinitionHandlerDependencies.Reference("edu:submit", "edu:approve", true),
                new DefinitionHandlerDependencies.Reference("edu:notify", "edu:send", true)), audit.references());
        assertTrue(audit.findings().isEmpty());
        assertEquals(List.of("handler-requirement-undeclared:edu:send"),
                DefinitionHandlerDependencies.inspect(manifest, Set.of("edu:approve")).findings());
    }
    @Test void neverTreatsImplicitDispatchAsNoDependency() throws Exception {
        var manifest = load("""
            [{"code":"edu:create","type":"create"},{"code":"edu:blank","handler":" "}]
            """);
        var audit = DefinitionHandlerDependencies.inspect(manifest, Set.of("edu:create"));
        assertEquals(List.of("implicit-handler-dispatch-unresolved:edu:create", "implicit-handler-dispatch-unresolved:edu:blank"), audit.findings());
        assertFalse(audit.references().getFirst().explicit());
        assertEquals("edu:create", audit.references().getFirst().handlerCode());
    }
    @Test void rejectsDuplicateCommandsAndAllowsExplicitEmptyResourceList() throws Exception {
        var duplicate = load("""
            [{"code":"edu:run","handler":"edu:one"},{"code":"edu:run","handler":"edu:two"}]
            """);
        assertThrows(IllegalArgumentException.class, () -> DefinitionHandlerDependencies.inspect(duplicate, Set.of()));
        var empty = load("[]");
        // The legacy loader collapses an empty commands resource to null; do not claim completeness from it.
        assertThrows(IllegalArgumentException.class, () -> DefinitionHandlerDependencies.inspect(empty, Set.of()));
        empty.setCommands(List.of());
        assertTrue(DefinitionHandlerDependencies.inspect(empty, Set.of()).references().isEmpty());
    }
}
