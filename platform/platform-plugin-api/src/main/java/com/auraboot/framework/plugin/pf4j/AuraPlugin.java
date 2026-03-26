package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.api.*;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for AuraBoot plugins using PF4J.
 * This class bridges the PF4J plugin lifecycle with the AuraBoot Plugin interface.
 *
 * Plugins should extend this class and implement the abstract methods:
 * <pre>
 * {@code
 * public class MyPlugin extends AuraPlugin {
 *     public MyPlugin(PluginWrapper wrapper) {
 *         super(wrapper);
 *     }
 *
 *     @Override
 *     public String getNamespace() {
 *         return "my-plugin";
 *     }
 *
 *     @Override
 *     protected void doInstall(PluginInstallContext context) {
 *         // Installation logic
 *     }
 *
 *     @Override
 *     protected void doEnable(PluginEnableContext context) {
 *         // Enable logic
 *     }
 *
 *     @Override
 *     protected void doDisable(PluginDisableContext context) {
 *         // Disable logic
 *     }
 *
 *     @Override
 *     protected void doUninstall(PluginUninstallContext context) {
 *         // Uninstall logic
 *     }
 * }
 * }
 * </pre>
 */
public abstract class AuraPlugin extends Plugin implements com.auraboot.framework.plugin.api.Plugin {

    private static final Logger log = LoggerFactory.getLogger(AuraPlugin.class);

    private final String pluginId;
    private final String version;

    private volatile boolean installed = false;
    private volatile boolean enabled = false;

    /**
     * Constructor for AuraPlugin.
     *
     * @param wrapper the PF4J plugin wrapper
     */
    protected AuraPlugin(PluginWrapper wrapper) {
        super(wrapper);
        this.pluginId = wrapper.getPluginId();
        this.version = wrapper.getDescriptor().getVersion();
        log.info("AuraPlugin created: {} v{}", pluginId, version);
    }

    // ========== Getters ==========

    @Override
    public String getPluginId() {
        return pluginId;
    }

    @Override
    public String getVersion() {
        return version;
    }

    // ========== PF4J Lifecycle Methods ==========

    @Override
    public void start() {
        log.info("Starting plugin: {}", pluginId);
        // PF4J start is called when plugin is loaded
        // We don't enable here - that's controlled by PluginManagerService
    }

    @Override
    public void stop() {
        log.info("Stopping plugin: {}", pluginId);
        // PF4J stop is called when plugin is unloaded
        // Actual disable is controlled by PluginManagerService
    }

    @Override
    public void delete() {
        log.info("Deleting plugin: {}", pluginId);
        // Called when plugin JAR is removed
    }

    // ========== AuraBoot Plugin Interface ==========

    @Override
    public abstract String getNamespace();

    @Override
    public final void onInstall(PluginInstallContext context) throws Exception {
        if (installed) {
            log.warn("Plugin {} is already installed, skipping onInstall", pluginId);
            return;
        }

        log.info("Installing plugin: {} (namespace: {})", pluginId, getNamespace());
        try {
            doInstall(context);
            installed = true;
            log.info("Plugin installed successfully: {}", pluginId);
        } catch (Exception e) {
            log.error("Failed to install plugin: {}", pluginId, e);
            throw e;
        }
    }

    @Override
    public final void onEnable(PluginEnableContext context) throws Exception {
        if (!installed) {
            log.warn("Plugin {} is not installed, cannot enable", pluginId);
            throw new IllegalStateException("Plugin must be installed before enabling");
        }

        if (enabled) {
            log.warn("Plugin {} is already enabled, skipping onEnable", pluginId);
            return;
        }

        log.info("Enabling plugin: {}", pluginId);
        try {
            doEnable(context);
            enabled = true;
            log.info("Plugin enabled successfully: {}", pluginId);
        } catch (Exception e) {
            log.error("Failed to enable plugin: {}", pluginId, e);
            throw e;
        }
    }

    @Override
    public final void onDisable(PluginDisableContext context) throws Exception {
        if (!enabled) {
            log.warn("Plugin {} is not enabled, skipping onDisable", pluginId);
            return;
        }

        log.info("Disabling plugin: {}", pluginId);
        try {
            doDisable(context);
            enabled = false;
            log.info("Plugin disabled successfully: {}", pluginId);
        } catch (Exception e) {
            log.error("Failed to disable plugin: {}", pluginId, e);
            throw e;
        }
    }

    @Override
    public final void onUninstall(PluginUninstallContext context) throws Exception {
        if (enabled) {
            log.warn("Plugin {} is still enabled, disabling before uninstall", pluginId);
            // Create a simple disable context for pre-uninstall disable
            onDisable(new SimpleDisableContext(
                    context.getTenantId(),
                    pluginId,
                    getNamespace(),
                    context.getVersion(),
                    context.getSettings(),
                    true // preUninstall
            ));
        }

        log.info("Uninstalling plugin: {} (removeData: {})", pluginId, context.shouldRemoveData());
        try {
            doUninstall(context);
            installed = false;
            log.info("Plugin uninstalled successfully: {}", pluginId);
        } catch (Exception e) {
            log.error("Failed to uninstall plugin: {}", pluginId, e);
            throw e;
        }
    }

    // ========== Abstract Methods for Subclasses ==========

    /**
     * Called when the plugin is installed.
     * Override to implement installation logic.
     *
     * @param context installation context
     * @throws Exception if installation fails
     */
    protected abstract void doInstall(PluginInstallContext context) throws Exception;

    /**
     * Called when the plugin is enabled.
     * Override to implement enable logic.
     *
     * @param context enable context
     * @throws Exception if enable fails
     */
    protected abstract void doEnable(PluginEnableContext context) throws Exception;

    /**
     * Called when the plugin is disabled.
     * Override to implement disable logic.
     *
     * @param context disable context
     * @throws Exception if disable fails
     */
    protected abstract void doDisable(PluginDisableContext context) throws Exception;

    /**
     * Called when the plugin is uninstalled.
     * Override to implement uninstall logic.
     *
     * @param context uninstall context
     * @throws Exception if uninstall fails
     */
    protected abstract void doUninstall(PluginUninstallContext context) throws Exception;

    // ========== Utility Methods ==========

    /**
     * Check if the plugin is currently installed.
     *
     * @return true if installed
     */
    public boolean isInstalled() {
        return installed;
    }

    /**
     * Check if the plugin is currently enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get the PF4J plugin wrapper.
     *
     * @return plugin wrapper
     */
    protected PluginWrapper getPluginWrapper() {
        return wrapper;
    }

    /**
     * Get the plugin's class loader.
     *
     * @return class loader
     */
    protected ClassLoader getPluginClassLoader() {
        return wrapper.getPluginClassLoader();
    }

    /**
     * Get the plugin's data directory path.
     *
     * @return data directory path
     */
    protected String getDataDirectory() {
        return wrapper.getPluginPath().getParent().resolve(pluginId + "-data").toString();
    }

    // ========== Simple Context Implementation ==========

    /**
     * Simple implementation of PluginDisableContext for internal use.
     * Used when disabling plugin before uninstall.
     */
    private static class SimpleDisableContext implements PluginDisableContext {
        private final Long tenantId;
        private final String pluginId;
        private final String namespace;
        private final String version;
        private final Map<String, Object> settings;
        private final boolean preUninstall;

        SimpleDisableContext(Long tenantId, String pluginId, String namespace,
                             String version, Map<String, Object> settings, boolean preUninstall) {
            this.tenantId = tenantId;
            this.pluginId = pluginId;
            this.namespace = namespace;
            this.version = version;
            this.settings = settings != null ? new HashMap<>(settings) : new HashMap<>();
            this.preUninstall = preUninstall;
        }

        @Override
        public Long getTenantId() { return tenantId; }

        @Override
        public String getPluginId() { return pluginId; }

        @Override
        public String getNamespace() { return namespace; }

        @Override
        public String getVersion() { return version; }

        @Override
        public Map<String, Object> getSettings() { return new HashMap<>(settings); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getSetting(String key) { return (T) settings.get(key); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getSetting(String key, T defaultValue) {
            Object value = settings.get(key);
            return value != null ? (T) value : defaultValue;
        }

        @Override
        public boolean isPreUninstall() { return preUninstall; }

        @Override
        public void unregisterScheduledTask(String taskId) {
            log.debug("Unregister scheduled task: {}", taskId);
        }

        @Override
        public void unregisterAllScheduledTasks() {
            log.debug("Unregister all scheduled tasks");
        }

        @Override
        public void unregisterEventListener(String eventType, String listenerClass) {
            log.debug("Unregister event listener: {} -> {}", eventType, listenerClass);
        }

        @Override
        public void unregisterAllEventListeners() {
            log.debug("Unregister all event listeners");
        }

        @Override
        public void log(String message) {
            AuraPlugin.log.info("Plugin {}: {}", pluginId, message);
        }
    }
}
