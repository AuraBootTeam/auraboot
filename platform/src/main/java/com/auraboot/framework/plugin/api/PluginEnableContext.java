package com.auraboot.framework.plugin.api;

/**
 * Context provided to plugins during enable phase.
 * Plugins should start their services and register handlers in this phase.
 */
public interface PluginEnableContext extends PluginContext {

    /**
     * Check if plugin was previously enabled (re-enable scenario).
     *
     * @return true if plugin was previously enabled
     */
    boolean wasEnabled();

    /**
     * Register a scheduled task for the plugin.
     *
     * @param taskId unique task identifier
     * @param cronExpression cron expression for scheduling
     * @param taskClass fully qualified task class name
     */
    void registerScheduledTask(String taskId, String cronExpression, String taskClass);

    /**
     * Register an event listener for the plugin.
     *
     * @param eventType event type to listen for
     * @param listenerClass fully qualified listener class name
     */
    void registerEventListener(String eventType, String listenerClass);

    /**
     * Log a plugin message during enable.
     *
     * @param message log message
     */
    void log(String message);
}
