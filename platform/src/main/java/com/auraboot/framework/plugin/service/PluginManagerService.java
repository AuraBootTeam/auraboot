package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.api.PluginRegistry;
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.PluginOperationResult;

import java.util.Map;

/**
 * Plugin lifecycle management service.
 * Handles install, enable, disable, and uninstall operations.
 */
public interface PluginManagerService extends PluginRegistry {

    /**
     * Install a plugin from manifest.
     *
     * @param manifest plugin manifest
     * @return operation result
     */
    PluginOperationResult install(PluginManifest manifest);

    /**
     * Enable an installed plugin.
     *
     * @param pluginId plugin identifier
     * @return operation result
     */
    PluginOperationResult enable(String pluginId);

    /**
     * Disable an enabled plugin.
     *
     * @param pluginId plugin identifier
     * @return operation result
     */
    PluginOperationResult disable(String pluginId);

    /**
     * Uninstall a plugin.
     *
     * @param pluginId plugin identifier
     * @param removeData whether to remove plugin data
     * @return operation result
     */
    PluginOperationResult uninstall(String pluginId, boolean removeData);

    /**
     * Update plugin settings.
     *
     * @param pluginId plugin identifier
     * @param settings new settings
     * @return operation result
     */
    PluginOperationResult updateSettings(String pluginId, Map<String, Object> settings);
}
