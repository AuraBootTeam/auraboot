package com.auraboot.framework.integration.plugin;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.api.Plugin;
import com.auraboot.framework.plugin.api.PluginDisableContext;
import com.auraboot.framework.plugin.api.PluginEnableContext;
import com.auraboot.framework.plugin.api.PluginInstallContext;
import com.auraboot.framework.plugin.api.PluginUninstallContext;
import com.auraboot.framework.plugin.dto.PluginInfo;
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.PluginOperationResult;
import com.auraboot.framework.plugin.dto.PluginStatus;
import com.auraboot.framework.plugin.exception.PluginLifecycleException;
import com.auraboot.framework.plugin.exception.PluginNotFoundException;
import com.auraboot.framework.plugin.service.PluginManagerService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for plugin lifecycle management.
 * Each test is independent and creates its own test data.
 */
@Slf4j
public class PluginLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PluginManagerService pluginManager;

    /**
     * Create a test plugin manifest with unique namespace.
     */
    private PluginManifest createTestManifest(String suffix) {
        String uniqueId = "com.test.plugin-" + suffix;
        String uniqueNamespace = "test-ns-" + suffix;
        return PluginManifest.builder()
                .pluginId(uniqueId)
                .namespace(uniqueNamespace)
                .version("1.0.0")
                .displayName("Sample Test Plugin " + suffix)
                .description("A sample plugin for integration testing")
                .author("Test Author")
                .defaultConfig(Map.of("key1", "value1", "key2", 123))
                .build();
    }

    /**
     * Create a test plugin implementation.
     */
    private Plugin createTestPlugin(String pluginId, String namespace) {
        return new Plugin() {
            @Override
            public String getPluginId() {
                return pluginId;
            }

            @Override
            public String getNamespace() {
                return namespace;
            }

            @Override
            public void onInstall(PluginInstallContext context) {
                log.info("Test plugin onInstall called for {}", pluginId);
                context.registerModel("test_model");
                context.registerCommand("test_command");
                context.reportProgress(100, "Installation complete");
            }

            @Override
            public void onEnable(PluginEnableContext context) {
                log.info("Test plugin onEnable called for {}, wasEnabled: {}", pluginId, context.wasEnabled());
                context.log("Plugin enabled successfully");
            }

            @Override
            public void onDisable(PluginDisableContext context) {
                log.info("Test plugin onDisable called for {}, preUninstall: {}", pluginId, context.isPreUninstall());
                context.log("Plugin disabled successfully");
            }

            @Override
            public void onUninstall(PluginUninstallContext context) {
                log.info("Test plugin onUninstall called for {}, removeData: {}", pluginId, context.shouldRemoveData());
                if (context.shouldRemoveData()) {
                    context.markModelForRemoval("test_model");
                    context.markCommandForRemoval("test_command");
                }
                context.reportProgress(100, "Uninstallation complete");
            }
        };
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void testInstallPlugin() {
        log.info("=== Test: Install Plugin ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Register plugin instance first
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);

        // Install plugin
        PluginOperationResult result = pluginManager.install(manifest);

        // Verify result
        assertTrue(result.isSuccess(), "Installation should succeed");
        assertNotNull(result.getPluginPid(), "Plugin PID should be set");
        assertEquals(manifest.getPluginId(), result.getPluginId());
        assertEquals(manifest.getNamespace(), result.getNamespace());
        assertEquals(PluginOperationResult.OperationType.INSTALL, result.getOperation());
        assertEquals(PluginStatus.INSTALLED, result.getCurrentStatus());

        // Verify plugin is stored
        Optional<PluginInfo> pluginInfo = pluginManager.getPluginByPluginId(manifest.getPluginId());
        assertTrue(pluginInfo.isPresent(), "Plugin should be found");
        assertEquals(PluginStatus.INSTALLED, pluginInfo.get().getStatus());

        log.info("Plugin installed with PID: {}", result.getPluginPid());
    }

    @Test
    void testDuplicateInstall() {
        log.info("=== Test: Duplicate Install ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Register and install first
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);
        PluginOperationResult firstResult = pluginManager.install(manifest);
        assertTrue(firstResult.isSuccess(), "First install should succeed");

        // Try to install the same plugin again
        PluginOperationResult secondResult = pluginManager.install(manifest);

        // Should fail because namespace is already used
        assertFalse(secondResult.isSuccess(), "Duplicate install should fail");
        assertNotNull(secondResult.getErrorMessage());
        log.info("Duplicate install correctly rejected: {}", secondResult.getErrorMessage());
    }

    @Test
    void testEnablePlugin() {
        log.info("=== Test: Enable Plugin ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Setup: install plugin first
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);
        pluginManager.install(manifest);

        // Enable the installed plugin
        PluginOperationResult result = pluginManager.enable(manifest.getPluginId());

        // Verify result
        assertTrue(result.isSuccess(), "Enable should succeed");
        assertEquals(PluginStatus.ENABLED, result.getCurrentStatus());
        assertEquals(PluginStatus.INSTALLED, result.getPreviousStatus());

        // Verify plugin status is updated
        Optional<PluginInfo> pluginInfo = pluginManager.getPluginByPluginId(manifest.getPluginId());
        assertTrue(pluginInfo.isPresent());
        assertEquals(PluginStatus.ENABLED, pluginInfo.get().getStatus());
        assertNotNull(pluginInfo.get().getEnabledAt(), "enabledAt should be set");

        // Verify plugin is in enabled list
        assertTrue(pluginManager.isPluginEnabled(manifest.getPluginId()));

        log.info("Plugin enabled successfully");
    }

    @Test
    void testEnableNonExistentPlugin() {
        log.info("=== Test: Enable Non-Existent Plugin ===");

        // Try to enable a plugin that doesn't exist
        assertThrows(PluginNotFoundException.class, () -> {
            pluginManager.enable("non-existent-plugin-" + uniqueSuffix());
        });

        log.info("Non-existent plugin correctly rejected");
    }

    @Test
    void testDisablePlugin() {
        log.info("=== Test: Disable Plugin ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Setup: install and enable plugin first
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);
        pluginManager.install(manifest);
        pluginManager.enable(manifest.getPluginId());

        // Disable the enabled plugin
        PluginOperationResult result = pluginManager.disable(manifest.getPluginId());

        // Verify result
        assertTrue(result.isSuccess(), "Disable should succeed");
        assertEquals(PluginStatus.DISABLED, result.getCurrentStatus());
        assertEquals(PluginStatus.ENABLED, result.getPreviousStatus());

        // Verify plugin status is updated
        Optional<PluginInfo> pluginInfo = pluginManager.getPluginByPluginId(manifest.getPluginId());
        assertTrue(pluginInfo.isPresent());
        assertEquals(PluginStatus.DISABLED, pluginInfo.get().getStatus());
        assertNotNull(pluginInfo.get().getDisabledAt(), "disabledAt should be set");

        // Verify plugin is not in enabled list
        assertFalse(pluginManager.isPluginEnabled(manifest.getPluginId()));
        assertTrue(pluginManager.isPluginInstalled(manifest.getPluginId()));

        log.info("Plugin disabled successfully");
    }

    @Test
    void testReEnablePlugin() {
        log.info("=== Test: Re-Enable Plugin ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Setup: install, enable, then disable
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);
        pluginManager.install(manifest);
        pluginManager.enable(manifest.getPluginId());
        pluginManager.disable(manifest.getPluginId());

        // Re-enable the disabled plugin
        PluginOperationResult result = pluginManager.enable(manifest.getPluginId());

        // Verify result
        assertTrue(result.isSuccess(), "Re-enable should succeed");
        assertEquals(PluginStatus.ENABLED, result.getCurrentStatus());

        log.info("Plugin re-enabled successfully");
    }

    @Test
    void testUpdateSettings() {
        log.info("=== Test: Update Settings ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Setup: install plugin
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);
        pluginManager.install(manifest);

        // Update plugin settings
        Map<String, Object> newSettings = Map.of(
                "key1", "newValue1",
                "key2", 456,
                "key3", true
        );
        PluginOperationResult result = pluginManager.updateSettings(manifest.getPluginId(), newSettings);

        // Verify result
        assertTrue(result.isSuccess(), "Update settings should succeed");

        // Verify settings are updated
        Optional<PluginInfo> pluginInfo = pluginManager.getPluginByPluginId(manifest.getPluginId());
        assertTrue(pluginInfo.isPresent());
        assertNotNull(pluginInfo.get().getSettings(), "Settings should not be null");
        assertEquals("newValue1", pluginInfo.get().getSettings().get("key1"));
        assertEquals(456, pluginInfo.get().getSettings().get("key2"));
        assertEquals(true, pluginInfo.get().getSettings().get("key3"));

        log.info("Plugin settings updated successfully");
    }

    @Test
    void testCannotUninstallEnabledPlugin() {
        log.info("=== Test: Cannot Uninstall Enabled Plugin ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Setup: install and enable plugin
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);
        pluginManager.install(manifest);
        pluginManager.enable(manifest.getPluginId());

        // Try to uninstall an enabled plugin
        assertThrows(PluginLifecycleException.class, () -> {
            pluginManager.uninstall(manifest.getPluginId(), false);
        });

        log.info("Uninstall of enabled plugin correctly rejected");
    }

    @Test
    void testUninstallPlugin() {
        log.info("=== Test: Uninstall Plugin ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Setup: install plugin (not enabled, so can uninstall)
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);
        pluginManager.install(manifest);

        // Uninstall the plugin
        PluginOperationResult result = pluginManager.uninstall(manifest.getPluginId(), true);

        // Verify result
        assertTrue(result.isSuccess(), "Uninstall should succeed");
        assertEquals(PluginOperationResult.OperationType.UNINSTALL, result.getOperation());

        // Verify plugin is removed
        assertFalse(pluginManager.isPluginInstalled(manifest.getPluginId()));
        Optional<PluginInfo> pluginInfo = pluginManager.getPluginByPluginId(manifest.getPluginId());
        assertTrue(pluginInfo.isEmpty(), "Plugin should not be found after uninstall");

        // Verify namespace is available again
        assertTrue(pluginManager.isNamespaceAvailable(manifest.getNamespace()));

        log.info("Plugin uninstalled successfully");
    }

    @Test
    void testUninstallDisabledPlugin() {
        log.info("=== Test: Uninstall Disabled Plugin ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Setup: install, enable, then disable
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);
        pluginManager.install(manifest);
        pluginManager.enable(manifest.getPluginId());
        pluginManager.disable(manifest.getPluginId());

        // Uninstall the disabled plugin
        PluginOperationResult result = pluginManager.uninstall(manifest.getPluginId(), true);

        // Verify result
        assertTrue(result.isSuccess(), "Uninstall disabled plugin should succeed");

        log.info("Disabled plugin uninstalled successfully");
    }

    @Test
    void testGetAllPlugins() {
        log.info("=== Test: Get All Plugins ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Install a new plugin
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);

        PluginOperationResult result = pluginManager.install(manifest);
        assertTrue(result.isSuccess());

        // Get all plugins
        List<PluginInfo> allPlugins = pluginManager.getAllPlugins();
        assertNotNull(allPlugins);
        assertTrue(allPlugins.size() >= 1, "Should have at least one plugin");

        // Verify our plugin is in the list
        boolean found = allPlugins.stream()
                .anyMatch(p -> p.getPluginId().equals(manifest.getPluginId()));
        assertTrue(found, "Our plugin should be in the list");

        log.info("Get all plugins test completed, found {} plugins", allPlugins.size());
    }

    @Test
    void testGetEnabledPlugins() {
        log.info("=== Test: Get Enabled Plugins ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Setup: install and enable plugin
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);
        pluginManager.install(manifest);
        pluginManager.enable(manifest.getPluginId());

        // Get enabled plugins
        List<PluginInfo> enabledPlugins = pluginManager.getEnabledPlugins();
        assertNotNull(enabledPlugins);

        // Verify our plugin is in the enabled list
        boolean found = enabledPlugins.stream()
                .anyMatch(p -> p.getPluginId().equals(manifest.getPluginId()));
        assertTrue(found, "Our plugin should be in enabled list");

        log.info("Get enabled plugins test completed");
    }

    @Test
    void testPluginStatusTransitions() {
        log.info("=== Test: Plugin Status Transitions ===");

        // Test status transition validation
        assertTrue(PluginStatus.INSTALLED.canTransitionTo(PluginStatus.ENABLED));
        assertTrue(PluginStatus.ENABLED.canTransitionTo(PluginStatus.DISABLED));
        assertTrue(PluginStatus.DISABLED.canTransitionTo(PluginStatus.ENABLED));

        assertFalse(PluginStatus.INSTALLED.canTransitionTo(PluginStatus.DISABLED));
        assertFalse(PluginStatus.DISABLED.canTransitionTo(PluginStatus.INSTALLED));

        // Test canUninstall
        assertTrue(PluginStatus.INSTALLED.canUninstall());
        assertTrue(PluginStatus.DISABLED.canUninstall());
        assertFalse(PluginStatus.ENABLED.canUninstall());

        // Test isActive
        assertTrue(PluginStatus.ENABLED.isActive());
        assertFalse(PluginStatus.INSTALLED.isActive());
        assertFalse(PluginStatus.DISABLED.isActive());

        log.info("Status transition tests completed");
    }

    @Test
    void testManifestValidation() {
        log.info("=== Test: Manifest Validation ===");

        // Valid manifest
        PluginManifest validManifest = PluginManifest.builder()
                .pluginId("test-id")
                .namespace("test-ns")
                .version("1.0.0")
                .build();
        assertTrue(validManifest.isValid());

        // Invalid manifests
        PluginManifest noPluginId = PluginManifest.builder()
                .namespace("test-ns")
                .version("1.0.0")
                .build();
        assertFalse(noPluginId.isValid());

        PluginManifest noNamespace = PluginManifest.builder()
                .pluginId("test-id")
                .version("1.0.0")
                .build();
        assertFalse(noNamespace.isValid());

        PluginManifest noVersion = PluginManifest.builder()
                .pluginId("test-id")
                .namespace("test-ns")
                .build();
        assertFalse(noVersion.isValid());

        PluginManifest emptyPluginId = PluginManifest.builder()
                .pluginId("")
                .namespace("test-ns")
                .version("1.0.0")
                .build();
        assertFalse(emptyPluginId.isValid());

        log.info("Manifest validation tests completed");
    }

    @Test
    void testNamespaceAvailability() {
        log.info("=== Test: Namespace Availability ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Namespace should be available before install
        assertTrue(pluginManager.isNamespaceAvailable(manifest.getNamespace()));

        // Install plugin
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);
        pluginManager.install(manifest);

        // Namespace should not be available after install
        assertFalse(pluginManager.isNamespaceAvailable(manifest.getNamespace()));

        // Uninstall
        pluginManager.uninstall(manifest.getPluginId(), true);

        // Namespace should be available again
        assertTrue(pluginManager.isNamespaceAvailable(manifest.getNamespace()));

        log.info("Namespace availability test completed");
    }

    @Test
    void testFullLifecycle() {
        log.info("=== Test: Full Lifecycle ===");
        String suffix = uniqueSuffix();
        PluginManifest manifest = createTestManifest(suffix);

        // Register plugin instance
        Plugin testPlugin = createTestPlugin(manifest.getPluginId(), manifest.getNamespace());
        pluginManager.registerPluginInstance(testPlugin);

        // 1. Install
        PluginOperationResult installResult = pluginManager.install(manifest);
        assertTrue(installResult.isSuccess());
        assertEquals(PluginStatus.INSTALLED, installResult.getCurrentStatus());

        // 2. Enable
        PluginOperationResult enableResult = pluginManager.enable(manifest.getPluginId());
        assertTrue(enableResult.isSuccess());
        assertEquals(PluginStatus.ENABLED, enableResult.getCurrentStatus());

        // 3. Disable
        PluginOperationResult disableResult = pluginManager.disable(manifest.getPluginId());
        assertTrue(disableResult.isSuccess());
        assertEquals(PluginStatus.DISABLED, disableResult.getCurrentStatus());

        // 4. Re-enable
        PluginOperationResult reEnableResult = pluginManager.enable(manifest.getPluginId());
        assertTrue(reEnableResult.isSuccess());
        assertEquals(PluginStatus.ENABLED, reEnableResult.getCurrentStatus());

        // 5. Disable again for uninstall
        pluginManager.disable(manifest.getPluginId());

        // 6. Uninstall
        PluginOperationResult uninstallResult = pluginManager.uninstall(manifest.getPluginId(), true);
        assertTrue(uninstallResult.isSuccess());

        // Verify completely removed
        assertFalse(pluginManager.isPluginInstalled(manifest.getPluginId()));

        log.info("Full lifecycle test completed");
    }
}
