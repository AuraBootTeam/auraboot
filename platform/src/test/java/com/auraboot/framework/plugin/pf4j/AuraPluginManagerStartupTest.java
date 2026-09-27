package com.auraboot.framework.plugin.pf4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.pf4j.PluginState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import static org.junit.jupiter.api.Assertions.*;

/** Real packaged JAR loading and PF4J lifecycle; no database or plugin command assertions. */
class AuraPluginManagerStartupTest {
    @TempDir Path directory;

    public static class GoodPlugin extends Plugin {
        public GoodPlugin(PluginWrapper wrapper) { super(wrapper); }
        public String marker() { return "loaded-from-jar"; }
    }
    public static class BrokenPlugin extends Plugin {
        public BrokenPlugin(PluginWrapper wrapper) { super(wrapper); }
        @Override public void start() { throw new IllegalStateException("fixture start failure"); }
    }

    private void jar(Class<?> pluginClass, String dependencies, Class<?>... extensions) throws Exception {
        var manifest = new Manifest();
        var attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Plugin-Id", "startup-fixture");
        attrs.putValue("Plugin-Version", "1.0.0");
        attrs.putValue("Plugin-Class", pluginClass.getName());
        if (dependencies != null) attrs.putValue("Plugin-Dependencies", dependencies);
        try (var output = new JarOutputStream(Files.newOutputStream(directory.resolve("fixture.jar")), manifest)) {
            for (Class<?> entry : java.util.stream.Stream.concat(java.util.stream.Stream.of(pluginClass), java.util.Arrays.stream(extensions)).toList()) {
                String resource = entry.getName().replace('.', '/') + ".class";
                output.putNextEntry(new JarEntry(resource));
                try (var bytes = entry.getClassLoader().getResourceAsStream(resource)) {
                    assertNotNull(bytes); bytes.transferTo(output);
                }
                output.closeEntry();
            }
            if (extensions.length > 0) {
                output.putNextEntry(new JarEntry("META-INF/extensions.idx"));
                output.write((java.util.Arrays.stream(extensions).map(Class::getName).collect(java.util.stream.Collectors.joining("\n")) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    public static class ContractHandler implements com.auraboot.framework.plugin.extension.CommandHandlerExtension {
        public String getCommandType() { return "fixture:contract"; }
        public java.util.Set<String> getSupportedContracts(String commandType) {
            return supports(commandType) ? java.util.Set.of("contract-v1") : java.util.Set.of();
        }
        public Object execute(CommandContext context) { throw new AssertionError("Inspection must not execute commands"); }
    }

    @Test void actualJarContractsFollowPluginLifecycleWithoutManualCacheRefresh() throws Exception {
        jar(GoodPlugin.class, null, ContractHandler.class);
        var manager = new AuraPluginManager(directory.toString());
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var registry = new ExtensionRegistry(manager,
                beans.getBeanProvider(com.auraboot.framework.plugin.extension.CommandHandlerExtension.class),
                beans.getBeanProvider(com.auraboot.framework.plugin.extension.ServiceTaskActionExtension.class));
        var inspector = new com.auraboot.framework.application.release.HandlerContractInspector(registry, manager);
        try {
            manager.init();
            var handler = registry.getCommandHandler("fixture:contract").orElseThrow();
            assertSame(manager.getPlugin("startup-fixture").getPluginClassLoader(), handler.getClass().getClassLoader());
            var artifact = manager.observeLoadedArtifact(handler.getClass()).orElseThrow();
            assertEquals("startup-fixture", artifact.pluginId());
            assertEquals("sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(directory.resolve("fixture.jar")))), artifact.digest());
            assertTrue(manager.observeLoadedArtifact(getClass()).isEmpty());
            var capability = inspector.observe("fixture:contract", "contract-v1");
            assertEquals(java.util.List.of(artifact.digest()), capability.capability().providerDigests());
            assertEquals("handler", capability.capability().kind());
            var definition = new com.auraboot.framework.plugin.dto.imports.PluginManifestExtended();
            var command = new com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO();
            command.setCode("fixture:business");
            command.setHandler("fixture:contract");
            definition.setCommands(java.util.List.of(command));
            var required = java.util.List.of(new com.auraboot.framework.application.release.HandlerContractInspector.Requirement("fixture:contract", "contract-v1"));
            var combined = inspector.observeDefinition(definition, required);
            assertTrue(combined.findings().isEmpty());
            assertEquals(java.util.List.of(capability.capability()), combined.capabilities());
            assertEquals("fixture:contract", combined.dependencies().references().getFirst().handlerCode());
            var definitionRoot = directory.resolve("pinned-definition");
            Files.createDirectories(definitionRoot.resolve("a"));
            Files.writeString(definitionRoot.resolve("a/commands.json"), "[{\"code\":\"fixture:business\",\"handler\":\"fixture:contract\"}]");
            Files.writeString(definitionRoot.resolve("a.json"), "{}");
            Files.writeString(definitionRoot.resolve("plugin.json"), "{\"pluginId\":\"test.pinned\",\"version\":\"1.0.0\",\"resourceDirs\":{\"commands\":\"a/commands.json\"}}");
            var pinnedObservation = inspector.observeDefinitionArtifact(definitionRoot,
                    "sha256:18fc4f981b31590710a71c38af3c72c13aa5a53d92eb72e96ec1be995e9645d5", required);
            assertTrue(pinnedObservation.observation().findings().isEmpty());
            assertEquals(java.util.List.of(capability.capability()), pinnedObservation.observation().capabilities());

            var undeclared = inspector.observeDefinition(definition, java.util.List.of());
            assertEquals(java.util.List.of("handler-requirement-undeclared:fixture:contract"), undeclared.findings());
            assertTrue(undeclared.capabilities().isEmpty());
            var multipleContracts = java.util.List.of(required.getFirst(),
                    new com.auraboot.framework.application.release.HandlerContractInspector.Requirement("fixture:contract", "contract-v2"));
            assertTrue(inspector.observeDefinition(definition, multipleContracts).capabilities().isEmpty());
            assertFalse(inspector.observeDefinition(definition, multipleContracts).findings().isEmpty());

            assertNull(inspector.observe("fixture:contract", "contract-v2").capability());
            assertTrue(inspector.inspect("fixture:contract", "contract-v1").supported());
            assertFalse(inspector.inspect("fixture:contract", "contract-v2").supported());
            assertEquals(PluginState.STOPPED, manager.stopPlugin("startup-fixture"));
            assertTrue(manager.observeLoadedArtifact(handler.getClass()).isEmpty());
            var stoppedObservation = inspector.observe("fixture:contract", "contract-v1");
            assertNull(stoppedObservation.capability());
            assertTrue(inspector.observeDefinition(definition, required).capabilities().isEmpty());
            assertFalse(inspector.observeDefinition(definition, required).findings().isEmpty());
            assertTrue(stoppedObservation.handlerGeneration() > capability.handlerGeneration());
            assertFalse(inspector.inspect("fixture:contract", "contract-v1").supported());
            assertTrue(registry.getCommandHandler("fixture:contract").isEmpty());
            assertEquals(PluginState.STARTED, manager.startPlugin("startup-fixture"));
            assertTrue(inspector.inspect("fixture:contract", "contract-v1").supported());
            assertEquals(artifact, manager.observeLoadedArtifact(handler.getClass()).orElseThrow());
            Files.writeString(directory.resolve("fixture.jar"), "changed", java.nio.file.StandardOpenOption.APPEND);
            assertThrows(IllegalStateException.class, () -> manager.observeLoadedArtifact(handler.getClass()));
            assertThrows(IllegalStateException.class, () -> inspector.observe("fixture:contract", "contract-v1"));
        } finally {
            registry.detachLifecycleListener();
            manager.cleanup();
            manager.unloadPlugins();
        }
    }

    @Test void healthyJarStartsAndUsesPluginClassLoader() throws Exception {
        jar(GoodPlugin.class, null);
        var manager = new AuraPluginManager(directory.toString());
        try {
            manager.init();
            var plugin = manager.getPlugin("startup-fixture");
            assertEquals(PluginState.STARTED, plugin.getPluginState());
            assertSame(plugin.getPluginClassLoader(), plugin.getPlugin().getClass().getClassLoader());
            assertEquals("loaded-from-jar", plugin.getPlugin().getClass().getMethod("marker").invoke(plugin.getPlugin()));
        } finally { manager.cleanup(); manager.unloadPlugins(); }
    }
    @Test void corruptJarCannotProduceEmptySuccessfulStartup() throws Exception {
        Files.writeString(directory.resolve("broken.jar"), "not a jar");
        var manager = new AuraPluginManager(directory.toString());
        assertThrows(IllegalStateException.class, manager::init);
        assertTrue(manager.getStartedPlugins().isEmpty());
    }
    @Test void failedStartStateFailsApplicationStartup() throws Exception {
        jar(BrokenPlugin.class, null);
        var manager = new AuraPluginManager(directory.toString());
        try {
            var error = assertThrows(IllegalStateException.class, manager::init);
            assertTrue(error.getCause().getMessage().contains("Plugin did not start"));
            assertEquals(PluginState.FAILED, manager.getPlugin("startup-fixture").getPluginState());
        } finally { manager.unloadPlugins(); }
    }
    @Test void missingDependencyFailsStartup() throws Exception {
        jar(GoodPlugin.class, "missing-plugin@1.0.0");
        var manager = new AuraPluginManager(directory.toString());
        assertThrows(IllegalStateException.class, manager::init);
        assertTrue(manager.getStartedPlugins().isEmpty());
    }
    @Test void emptyPluginDirectoryIsValidForCoreOnlyBoot() {
        var manager = new AuraPluginManager(directory.toString());
        manager.init();
        assertTrue(manager.getPlugins().isEmpty());
    }
    @Test void fileCannotMasqueradeAsAnEmptyPluginRoot() throws Exception {
        Path file = directory.resolve("not-a-directory");
        Files.writeString(file, "fixture");
        var manager = new AuraPluginManager(file.toString());
        assertThrows(IllegalStateException.class, manager::init);
    }
    @Test void initializationFailurePreventsSpringContextRefresh() throws Exception {
        Files.writeString(directory.resolve("broken.jar"), "not a jar");
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(AuraPluginManager.class, () -> new AuraPluginManager(directory.toString()));
            assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh);
            assertFalse(context.isActive());
        }
    }
    @Test void explicitlyDisabledPluginIsNotStarted() throws Exception {
        jar(GoodPlugin.class, null);
        Files.writeString(directory.resolve("disabled.txt"), "startup-fixture\n");
        var manager = new AuraPluginManager(directory.toString());
        try {
            manager.init();
            assertEquals(PluginState.DISABLED, manager.getPlugin("startup-fixture").getPluginState());
            assertTrue(manager.getStartedPlugins().isEmpty());
        } finally { manager.unloadPlugins(); }
    }
}
