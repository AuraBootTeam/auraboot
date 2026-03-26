package com.auraboot.framework.plugin.api;

import java.util.Map;

/**
 * Base context interface for plugin lifecycle callbacks.
 * Provides access to tenant context and plugin configuration.
 */
public interface PluginContext {

    /**
     * Get the tenant ID.
     *
     * @return tenant ID
     */
    Long getTenantId();

    /**
     * Get the plugin ID.
     *
     * @return plugin ID
     */
    String getPluginId();

    /**
     * Get the plugin namespace.
     *
     * @return plugin namespace
     */
    String getNamespace();

    /**
     * Get the plugin version.
     *
     * @return plugin version
     */
    String getVersion();

    /**
     * Get plugin settings.
     *
     * @return settings map or empty map if no settings
     */
    Map<String, Object> getSettings();

    /**
     * Get a specific setting value.
     *
     * @param key setting key
     * @param <T> expected value type
     * @return setting value or null
     */
    <T> T getSetting(String key);

    /**
     * Get a specific setting value with default.
     *
     * @param key setting key
     * @param defaultValue default value if not found
     * @param <T> expected value type
     * @return setting value or default
     */
    <T> T getSetting(String key, T defaultValue);
}
