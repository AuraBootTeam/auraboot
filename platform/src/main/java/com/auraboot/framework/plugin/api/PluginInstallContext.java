package com.auraboot.framework.plugin.api;

import com.auraboot.framework.plugin.dto.PluginManifest;

/**
 * Context provided to plugins during installation phase.
 * Extends base context with installation-specific information.
 */
public interface PluginInstallContext extends PluginContext {

    /**
     * Get the full plugin manifest.
     *
     * @return plugin manifest
     */
    PluginManifest getManifest();

    /**
     * Check if this is a fresh install (not an upgrade).
     *
     * @return true if fresh install
     */
    boolean isFreshInstall();

    /**
     * Get the previous version if this is an upgrade.
     *
     * @return previous version or null if fresh install
     */
    String getPreviousVersion();

    /**
     * Register a model code that the plugin will manage.
     * This allows the platform to track which models belong to the plugin.
     *
     * @param modelCode model code to register
     */
    void registerModel(String modelCode);

    /**
     * Register a command code that the plugin will provide.
     *
     * @param commandCode command code to register
     */
    void registerCommand(String commandCode);

    /**
     * Report installation progress.
     *
     * @param percentage completion percentage (0-100)
     * @param message progress message
     */
    void reportProgress(int percentage, String message);
}
