package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.service.impl.PluginDirectoryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PinnedPluginSourceTest {
    @TempDir Path root;
    // Generated independently with application-contract.mjs sha256Path, including DFS ordering a/ before a.json.
    static final String DIGEST = "sha256:18fc4f981b31590710a71c38af3c72c13aa5a53d92eb72e96ec1be995e9645d5";
    private void fixture() throws Exception {
        Files.createDirectory(root.resolve("a"));
        Files.writeString(root.resolve("a/commands.json"), "[{\"code\":\"fixture:business\",\"handler\":\"fixture:contract\"}]");
        Files.writeString(root.resolve("a.json"), "{}");
        Files.writeString(root.resolve("plugin.json"), "{\"pluginId\":\"test.pinned\",\"version\":\"1.0.0\",\"resourceDirs\":{\"commands\":\"a/commands.json\"}}");
    }
    @Test void matchesPublishedDirectoryDigestAndLoadsOnlyCapturedBytes() throws Exception {
        fixture();
        var source = PinnedPluginSource.capture(root, DIGEST);
        Files.writeString(root.resolve("a/commands.json"), "[]");
        var definition = new PluginDirectoryLoader().loadFromSource(source);
        assertEquals(DIGEST, source.digest());
        assertEquals("fixture:contract", definition.getCommands().getFirst().getHandler());
        assertEquals(List.of("a/commands.json"), source.listFiles("a", ".json"));
        assertTrue(source.exists("a"));
        assertThrows(IllegalArgumentException.class, () -> PinnedPluginSource.capture(root, DIGEST));
    }
    @Test void rejectsUnknownDigestTraversalAndSymlinks() throws Exception {
        fixture();
        assertThrows(IllegalArgumentException.class, () -> PinnedPluginSource.capture(root, "sha256:" + "0".repeat(64)));
        var source = PinnedPluginSource.capture(root, DIGEST);
        assertThrows(IllegalArgumentException.class, () -> source.readResource("../plugin.json"));
        assertThrows(IllegalArgumentException.class, () -> source.exists(root.toString()));
        Files.createSymbolicLink(root.resolve("link"), root.resolve("plugin.json"));
        assertThrows(java.io.IOException.class, () -> PinnedPluginSource.capture(root, DIGEST));
    }
    @Test void pinnedArtifactEntryFeedsDefinitionAndActualHandlerInspection() throws Exception {
        fixture();
        var registry = org.mockito.Mockito.mock(com.auraboot.framework.plugin.pf4j.ExtensionRegistry.class);
        var manager = org.mockito.Mockito.mock(com.auraboot.framework.plugin.pf4j.AuraPluginManager.class);
        var inspector = new HandlerContractInspector(registry, manager);
        var result = inspector.observeDefinitionArtifact(root, DIGEST, List.of());
        assertEquals(DIGEST, result.definitionDigest());
        assertEquals(List.of("handler-requirement-undeclared:fixture:contract"), result.observation().findings());
        assertTrue(result.observation().capabilities().isEmpty());
        org.mockito.Mockito.verifyNoInteractions(manager);
    }
    @Test void strictSourceRejectsMalformedAndNullDirectoryResources() throws Exception {
        fixture();
        Files.writeString(root.resolve("plugin.json"), "{\"pluginId\":\"test.pinned\",\"version\":\"1.0.0\",\"resourceDirs\":{\"commands\":\"a\"}}");
        Files.delete(root.resolve("a.json"));
        var strictSource = new com.auraboot.framework.plugin.source.FileSystemPluginSource(root) {
            @Override public boolean requiresCompleteResources() { return true; }
        };
        for (var bad : List.of("{broken", "null")) {
            Files.writeString(root.resolve("a/bad.json"), bad);
            assertThrows(com.auraboot.framework.plugin.exception.PluginException.class,
                    () -> new PluginDirectoryLoader().loadFromSource(strictSource));
        }
        Files.delete(root.resolve("a/bad.json"));
        assertEquals(1, new PluginDirectoryLoader().loadFromSource(strictSource).getCommands().size());
    }

    @Test void strictSourceRejectsMissingUnknownAndAmbiguousResourceDeclarations() throws Exception {
        fixture();
        var source = new com.auraboot.framework.plugin.source.FileSystemPluginSource(root) {
            @Override public boolean requiresCompleteResources() { return true; }
        };
        for (var declarations : List.of("\"commands\":\"absent.json\"", "\"unknownType\":\"a/commands.json\"",
                "\"bindings\":\"a/commands.json\",\"modelFieldBindings\":\"a/commands.json\"")) {
            Files.writeString(root.resolve("plugin.json"), "{\"pluginId\":\"test.pinned\",\"version\":\"1.0.0\",\"resourceDirs\":{" + declarations + "}}");
            assertThrows(com.auraboot.framework.plugin.exception.PluginException.class,
                    () -> new PluginDirectoryLoader().loadFromSource(source));
        }
    }
    @Test void strictSourceValidatesButDoesNotDeserializeDefaultBootstrapData() throws Exception {
        fixture();
        Files.writeString(root.resolve("default-bootstrap.json"), "{\"records\":[]}");
        Files.writeString(root.resolve("plugin.json"), """
                {"pluginId":"test.pinned","version":"1.0.0","resourceDirs":{
                  "commands":"a/commands.json","defaultBootstrap":"default-bootstrap.json"
                }}
                """);
        var source = new com.auraboot.framework.plugin.source.FileSystemPluginSource(root) {
            @Override public boolean requiresCompleteResources() { return true; }
        };

        var manifest = new PluginDirectoryLoader().loadFromSource(source);

        assertEquals("fixture:contract", manifest.getCommands().getFirst().getHandler());
    }
    @Test void strictSourceRetainsExplicitEmptyCommandsAndRejectsNullList() throws Exception {
        fixture();
        var source = new com.auraboot.framework.plugin.source.FileSystemPluginSource(root) {
            @Override public boolean requiresCompleteResources() { return true; }
        };
        Files.writeString(root.resolve("a/commands.json"), "[]");
        var manifest = new PluginDirectoryLoader().loadFromSource(source);
        assertNotNull(manifest.getCommands());
        assertTrue(DefinitionHandlerDependencies.inspect(manifest, java.util.Set.of()).references().isEmpty());
        Files.writeString(root.resolve("a/commands.json"), "null");
        assertThrows(com.auraboot.framework.plugin.exception.PluginException.class,
                () -> new PluginDirectoryLoader().loadFromSource(source));
    }

}
