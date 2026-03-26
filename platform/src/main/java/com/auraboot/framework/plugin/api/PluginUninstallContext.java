package com.auraboot.framework.plugin.api;

/**
 * Context provided to plugins during uninstall phase.
 * Plugins should cleanup all data and resources in this phase.
 */
public interface PluginUninstallContext extends PluginContext {

    /**
     * Check if plugin data should be removed.
     * If false, only the plugin code is removed but data is preserved.
     *
     * @return true if data should be removed
     */
    boolean shouldRemoveData();

    /**
     * Mark a model for removal.
     * The platform will drop the model's physical table if shouldRemoveData() is true.
     *
     * @param modelCode model code to remove
     */
    void markModelForRemoval(String modelCode);

    /**
     * Mark a command for removal.
     *
     * @param commandCode command code to remove
     */
    void markCommandForRemoval(String commandCode);

    /**
     * Report uninstall progress.
     *
     * @param percentage completion percentage (0-100)
     * @param message progress message
     */
    void reportProgress(int percentage, String message);

    /**
     * Log a plugin message during uninstall.
     *
     * @param message log message
     */
    void log(String message);
}
