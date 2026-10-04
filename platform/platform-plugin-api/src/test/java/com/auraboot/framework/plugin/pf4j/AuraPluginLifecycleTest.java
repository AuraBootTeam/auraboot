package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.api.*;
import org.pf4j.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class AuraPluginLifecycleTest {
    @TempDir Path root;

    @Test
    void lifecycleIsOrderedAndInstallEnableDisableAreIdempotent() throws Exception {
        var plugin = plugin();
        assertThrows(IllegalStateException.class, () -> plugin.onEnable(null));
        plugin.onDisable(null); assertEquals(List.of(), plugin.calls);
        plugin.start(); plugin.stop(); plugin.delete(); assertFalse(plugin.isInstalled()); assertFalse(plugin.isEnabled());
        plugin.onInstall(null); plugin.onInstall(null); assertTrue(plugin.isInstalled());
        plugin.onEnable(null); plugin.onEnable(null); assertTrue(plugin.isEnabled());
        plugin.onDisable(null); plugin.onDisable(null); assertFalse(plugin.isEnabled());
        plugin.onUninstall(uninstall(Map.of())); assertFalse(plugin.isInstalled());
        assertEquals(List.of("install", "enable", "disable", "uninstall"), plugin.calls);
        assertEquals("test.plugin", plugin.getPluginId()); assertEquals("1.2.3", plugin.getVersion());
        assertEquals(root.resolve("test.plugin-data").toString(), plugin.dataDirectory());
        assertSame(plugin.wrapper(), plugin.getPluginWrapper());
        assertSame(getClass().getClassLoader(), plugin.classLoader());
    }

    @Test
    void failedInstallNeverSetsInstalledAndCanBeRetried() throws Exception {
        var plugin = plugin(); plugin.fail = "install";
        assertThrows(IllegalStateException.class, () -> plugin.onInstall(null));
        assertFalse(plugin.isInstalled()); assertFalse(plugin.isEnabled());
        plugin.fail = null; plugin.onInstall(null); assertTrue(plugin.isInstalled());
    }

    @Test
    void failedEnableKeepsInstalledButDoesNotEnable() throws Exception {
        var plugin = plugin(); plugin.onInstall(null); plugin.fail = "enable";
        assertThrows(IllegalStateException.class, () -> plugin.onEnable(null));
        assertTrue(plugin.isInstalled()); assertFalse(plugin.isEnabled());
        plugin.fail = null; plugin.onEnable(null); assertTrue(plugin.isEnabled());
    }

    @Test
    void failedDisableDoesNotClearEnabledAndPreventsUninstall() throws Exception {
        var plugin = plugin(); plugin.onInstall(null); plugin.onEnable(null); plugin.fail = "disable";
        assertThrows(IllegalStateException.class, () -> plugin.onDisable(null)); assertTrue(plugin.isEnabled());
        assertThrows(IllegalStateException.class, () -> plugin.onUninstall(uninstall(Map.of())));
        assertTrue(plugin.isInstalled()); assertTrue(plugin.isEnabled());
        assertFalse(plugin.calls.contains("uninstall"));
    }

    @Test
    void failedUninstallKeepsInstalledAfterTheRequiredDisable() throws Exception {
        var plugin = plugin(); plugin.onInstall(null); plugin.onEnable(null); plugin.fail = "uninstall";
        assertThrows(IllegalStateException.class, () -> plugin.onUninstall(uninstall(Map.of())));
        assertTrue(plugin.isInstalled()); assertFalse(plugin.isEnabled());
        plugin.fail = null; plugin.onUninstall(uninstall(Map.of())); assertFalse(plugin.isInstalled());
    }

    @Test
    void uninstallSuppliesDefensivePreUninstallContextForAnEnabledPlugin() throws Exception {
        var plugin = plugin(); plugin.onInstall(null); plugin.onEnable(null);
        var settings = new HashMap<String, Object>(); settings.put("fixture", "value");
        plugin.onUninstall(uninstall(settings));
        var context = plugin.disabledContext;
        assertNotNull(context); assertTrue(context.isPreUninstall());
        assertEquals(7L, context.getTenantId()); assertEquals("test.plugin", context.getPluginId());
        assertEquals("test", context.getNamespace()); assertEquals("1.2.3", context.getVersion());
        assertEquals("value", context.getSetting("fixture")); assertNull(context.getSetting("absent"));
        assertEquals("value", context.getSetting("fixture", "default")); assertEquals("default", context.getSetting("absent", "default"));
        context.getSettings().put("fixture", "changed"); settings.put("fixture", "changed externally");
        assertEquals("value", context.getSetting("fixture"));
        assertEquals(List.of("install", "enable", "disable", "uninstall"), plugin.calls);
        assertFalse(plugin.isInstalled()); assertFalse(plugin.isEnabled());
    }

    @Test
    void preUninstallContextNormalizesMissingSettings() throws Exception {
        var plugin = plugin(); plugin.onInstall(null); plugin.onEnable(null); plugin.onUninstall(uninstall(null));
        assertEquals(Map.of(), plugin.disabledContext.getSettings());
    }

    private FixturePlugin plugin() {
        var descriptor = new DefaultPluginDescriptor() { { setPluginId("test.plugin"); setPluginVersion("1.2.3"); } };
        var wrapper = new PluginWrapper(new DefaultPluginManager(root), descriptor, root.resolve("plugin.jar"), getClass().getClassLoader());
        return new FixturePlugin(wrapper);
    }
    private PluginUninstallContext uninstall(Map<String, Object> settings) {
        return (PluginUninstallContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PluginUninstallContext.class}, (p, m, a) -> switch (m.getName()) {
            case "getTenantId" -> 7L;
            case "getPluginId" -> "test.plugin";
            case "getNamespace" -> "test";
            case "getVersion" -> "1.2.3";
            case "getSettings" -> settings;
            case "shouldRemoveData" -> false;
            default -> throw new AssertionError("Unexpected context invocation: " + m.getName());
        });
    }
    private static class FixturePlugin extends AuraPlugin {
        final List<String> calls = new ArrayList<>(); String fail; PluginDisableContext disabledContext;
        FixturePlugin(PluginWrapper wrapper) { super(wrapper); }
        public String getNamespace() { return "test"; }
        protected void doInstall(PluginInstallContext context) { called("install"); }
        protected void doEnable(PluginEnableContext context) { called("enable"); }
        protected void doDisable(PluginDisableContext context) { disabledContext = context; called("disable"); }
        protected void doUninstall(PluginUninstallContext context) { called("uninstall"); }
        private void called(String step) { calls.add(step); if (step.equals(fail)) throw new IllegalStateException("Fixture " + step + " failure"); }
        String dataDirectory() { return getDataDirectory(); }
        ClassLoader classLoader() { return getPluginClassLoader(); }
        PluginWrapper wrapper() { return getPluginWrapper(); }
    }
}
