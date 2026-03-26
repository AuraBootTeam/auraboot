package com.auraboot.framework.plugin.exception;

/**
 * Exception thrown when a plugin is not found.
 */
public class PluginNotFoundException extends PluginException {

    private static final long serialVersionUID = 1L;

    public PluginNotFoundException(String pluginId) {
        super("Plugin not found: " + pluginId, pluginId, null);
    }

    public PluginNotFoundException(String pluginId, String namespace) {
        super("Plugin not found: " + pluginId, pluginId, namespace);
    }

    public static PluginNotFoundException byPid(String pid) {
        return new PluginNotFoundException("Plugin not found with PID: " + pid);
    }

    public static PluginNotFoundException byNamespace(String namespace) {
        PluginNotFoundException ex = new PluginNotFoundException(null, namespace);
        return ex;
    }
}
