package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.api.*;
import com.auraboot.framework.plugin.service.impl.DefaultPluginContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

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
@Slf4j
public abstract class AuraPlugin extends Plugin implements com.auraboot.framework.plugin.api.Plugin {

    @Getter
    private final String pluginId;

    @Getter
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
            onDisable(DefaultPluginContext.builder()
                    .tenantId(context.getTenantId())
                    .pluginId(pluginId)
                    .namespace(getNamespace())
                    .preUninstall(true)
                    .build());
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
}
