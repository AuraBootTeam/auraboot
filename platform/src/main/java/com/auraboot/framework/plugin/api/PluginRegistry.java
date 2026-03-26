package com.auraboot.framework.plugin.api;

import com.auraboot.framework.plugin.dto.PluginInfo;
import com.auraboot.framework.plugin.dto.PluginStatus;

import java.util.List;
import java.util.Optional;

/**
 * Plugin registry interface for querying installed plugins.
 */
public interface PluginRegistry {

    /**
     * Get plugin info by PID.
     *
     * @param pid plugin PID
     * @return plugin info or empty if not found
     */
    Optional<PluginInfo> getPluginByPid(String pid);

    /**
     * Get plugin info by namespace.
     *
     * @param namespace plugin namespace
     * @return plugin info or empty if not found
     */
    Optional<PluginInfo> getPluginByNamespace(String namespace);

    /**
     * Get plugin info by plugin ID.
     *
     * @param pluginId plugin identifier
     * @return plugin info or empty if not found
     */
    Optional<PluginInfo> getPluginByPluginId(String pluginId);

    /**
     * Get all plugins for the current tenant.
     *
     * @return list of plugin info
     */
    List<PluginInfo> getAllPlugins();

    /**
     * Get plugins by status.
     *
     * @param status plugin status
     * @return list of plugin info
     */
    List<PluginInfo> getPluginsByStatus(PluginStatus status);

    /**
     * Get all enabled plugins.
     *
     * @return list of enabled plugin info
     */
    List<PluginInfo> getEnabledPlugins();

    /**
     * Check if a plugin is installed.
     *
     * @param pluginId plugin identifier
     * @return true if installed
     */
    boolean isPluginInstalled(String pluginId);

    /**
     * Check if a plugin is enabled.
     *
     * @param pluginId plugin identifier
     * @return true if enabled
     */
    boolean isPluginEnabled(String pluginId);

    /**
     * Check if a namespace is available (not used by another plugin).
     *
     * @param namespace namespace to check
     * @return true if available
     */
    boolean isNamespaceAvailable(String namespace);

    /**
     * Get the Plugin implementation instance for a plugin.
     * Returns empty if plugin is not installed or does not have an implementation.
     *
     * @param pluginId plugin identifier
     * @return plugin instance or empty
     */
    Optional<Plugin> getPluginInstance(String pluginId);

    /**
     * Register a Plugin implementation.
     * Called internally when a plugin is installed.
     *
     * @param plugin plugin instance
     */
    void registerPluginInstance(Plugin plugin);

    /**
     * Unregister a Plugin implementation.
     * Called internally when a plugin is uninstalled.
     *
     * @param pluginId plugin identifier
     */
    void unregisterPluginInstance(String pluginId);
}
