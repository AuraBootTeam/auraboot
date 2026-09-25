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

    private void jar(Class<?> pluginClass, String dependencies) throws Exception {
        var manifest = new Manifest();
        var attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Plugin-Id", "startup-fixture");
        attrs.putValue("Plugin-Version", "1.0.0");
        attrs.putValue("Plugin-Class", pluginClass.getName());
        if (dependencies != null) attrs.putValue("Plugin-Dependencies", dependencies);
        try (var output = new JarOutputStream(Files.newOutputStream(directory.resolve("fixture.jar")), manifest)) {
            String resource = pluginClass.getName().replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(resource));
            try (var bytes = pluginClass.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(bytes); bytes.transferTo(output);
            }
            output.closeEntry();
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
