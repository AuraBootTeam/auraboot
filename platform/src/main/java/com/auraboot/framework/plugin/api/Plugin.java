package com.auraboot.framework.plugin.api;

/**
 * Plugin lifecycle interface.
 * Plugins must implement this interface to participate in lifecycle management.
 *
 * Lifecycle flow:
 * 1. install() - Called when plugin is first installed
 * 2. enable()  - Called when plugin is enabled (made active)
 * 3. disable() - Called when plugin is disabled (paused)
 * 4. uninstall() - Called when plugin is removed
 *
 * State machine:
 * INSTALLED -> ENABLED <-> DISABLED -> (uninstall)
 */
public interface Plugin {

    /**
     * Get the unique plugin identifier.
     * This should match the pluginId in the manifest.
     *
     * @return plugin ID (e.g., "com.example.my-plugin")
     */
    String getPluginId();

    /**
     * Get the plugin namespace.
     * The namespace is used for resource isolation and must be unique within a tenant.
     *
     * @return plugin namespace
     */
    String getNamespace();

    /**
     * Called when the plugin is installed.
     * Use this to:
     * - Create models and fields
     * - Import initial data
     * - Register commands
     * - Set up initial configuration
     *
     * @param context installation context
     * @throws Exception if installation fails
     */
    void onInstall(PluginInstallContext context) throws Exception;

    /**
     * Called when the plugin is enabled.
     * Use this to:
     * - Start background services
     * - Register event listeners
     * - Schedule tasks
     * - Initialize caches
     *
     * @param context enable context
     * @throws Exception if enable fails
     */
    void onEnable(PluginEnableContext context) throws Exception;

    /**
     * Called when the plugin is disabled.
     * Use this to:
     * - Stop background services
     * - Unregister event listeners
     * - Cancel scheduled tasks
     * - Cleanup caches
     *
     * @param context disable context
     * @throws Exception if disable fails
     */
    void onDisable(PluginDisableContext context) throws Exception;

    /**
     * Called when the plugin is uninstalled.
     * Use this to:
     * - Remove models and data (if requested)
     * - Cleanup all resources
     * - Remove configuration
     *
     * @param context uninstall context
     * @throws Exception if uninstall fails
     */
    void onUninstall(PluginUninstallContext context) throws Exception;

    /**
     * Get the plugin version.
     * Default implementation returns "1.0.0".
     *
     * @return plugin version in semver format
     */
    default String getVersion() {
        return "1.0.0";
    }

    /**
     * Get the plugin display name.
     * Default implementation returns the plugin ID.
     *
     * @return display name
     */
    default String getDisplayName() {
        return getPluginId();
    }

    /**
     * Get the plugin description.
     * Default implementation returns empty string.
     *
     * @return description
     */
    default String getDescription() {
        return "";
    }

    /**
     * Get the plugin author.
     * Default implementation returns "Unknown".
     *
     * @return author name
     */
    default String getAuthor() {
        return "Unknown";
    }
}
