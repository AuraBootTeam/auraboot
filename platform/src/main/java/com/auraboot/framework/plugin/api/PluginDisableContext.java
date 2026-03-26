package com.auraboot.framework.plugin.api;

/**
 * Context provided to plugins during disable phase.
 * Plugins should stop their services and cleanup resources in this phase.
 */
public interface PluginDisableContext extends PluginContext {

    /**
     * Check if plugin is being disabled permanently (will be uninstalled).
     *
     * @return true if plugin will be uninstalled after disable
     */
    boolean isPreUninstall();

    /**
     * Unregister a scheduled task.
     *
     * @param taskId task identifier to unregister
     */
    void unregisterScheduledTask(String taskId);

    /**
     * Unregister all scheduled tasks for this plugin.
     */
    void unregisterAllScheduledTasks();

    /**
     * Unregister an event listener.
     *
     * @param eventType event type
     * @param listenerClass listener class
     */
    void unregisterEventListener(String eventType, String listenerClass);

    /**
     * Unregister all event listeners for this plugin.
     */
    void unregisterAllEventListeners();

    /**
     * Log a plugin message during disable.
     *
     * @param message log message
     */
    void log(String message);
}
