package com.auraboot.framework.plugin.exception;

import com.auraboot.framework.plugin.dto.PluginStatus;

/**
 * Exception thrown when a plugin lifecycle operation fails.
 */
public class PluginLifecycleException extends PluginException {

    private static final long serialVersionUID = 1L;

    private final PluginStatus currentStatus;
    private final PluginStatus targetStatus;
    private final String operation;

    public PluginLifecycleException(String message, String pluginId, String namespace,
                                     PluginStatus currentStatus, PluginStatus targetStatus,
                                     String operation) {
        super(message, pluginId, namespace);
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
        this.operation = operation;
    }

    public PluginLifecycleException(String message, String pluginId, String namespace,
                                     PluginStatus currentStatus, PluginStatus targetStatus,
                                     String operation, Throwable cause) {
        super(message, pluginId, namespace, cause);
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
        this.operation = operation;
    }

    public PluginStatus getCurrentStatus() {
        return currentStatus;
    }

    public PluginStatus getTargetStatus() {
        return targetStatus;
    }

    public String getOperation() {
        return operation;
    }

    /**
     * Create exception for invalid state transition.
     */
    public static PluginLifecycleException invalidTransition(String pluginId, String namespace,
                                                              PluginStatus currentStatus,
                                                              PluginStatus targetStatus) {
        String message = String.format("Invalid state transition from %s to %s", currentStatus, targetStatus);
        return new PluginLifecycleException(message, pluginId, namespace, currentStatus, targetStatus, "transition");
    }

    /**
     * Create exception for enable failure.
     */
    public static PluginLifecycleException enableFailed(String pluginId, String namespace,
                                                         PluginStatus currentStatus, Throwable cause) {
        String message = "Failed to enable plugin";
        return new PluginLifecycleException(message, pluginId, namespace, currentStatus, PluginStatus.ENABLED, "enable", cause);
    }

    /**
     * Create exception for disable failure.
     */
    public static PluginLifecycleException disableFailed(String pluginId, String namespace,
                                                          PluginStatus currentStatus, Throwable cause) {
        String message = "Failed to disable plugin";
        return new PluginLifecycleException(message, pluginId, namespace, currentStatus, PluginStatus.DISABLED, "disable", cause);
    }

    /**
     * Create exception for install failure.
     */
    public static PluginLifecycleException installFailed(String pluginId, String namespace, Throwable cause) {
        String message = "Failed to install plugin";
        return new PluginLifecycleException(message, pluginId, namespace, null, PluginStatus.INSTALLED, "install", cause);
    }

    /**
     * Create exception for uninstall failure.
     */
    public static PluginLifecycleException uninstallFailed(String pluginId, String namespace,
                                                            PluginStatus currentStatus, Throwable cause) {
        String message = "Failed to uninstall plugin";
        return new PluginLifecycleException(message, pluginId, namespace, currentStatus, null, "uninstall", cause);
    }

    /**
     * Create exception when plugin is already installed.
     */
    public static PluginLifecycleException alreadyInstalled(String pluginId, String namespace) {
        String message = "Plugin is already installed";
        return new PluginLifecycleException(message, pluginId, namespace, PluginStatus.INSTALLED, PluginStatus.INSTALLED, "install");
    }

    /**
     * Create exception when trying to uninstall an enabled plugin.
     */
    public static PluginLifecycleException cannotUninstallEnabled(String pluginId, String namespace) {
        String message = "Cannot uninstall enabled plugin. Disable it first.";
        return new PluginLifecycleException(message, pluginId, namespace, PluginStatus.ENABLED, null, "uninstall");
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder(super.getMessage());
        sb.append(" [operation=").append(operation);
        if (currentStatus != null) {
            sb.append(", currentStatus=").append(currentStatus);
        }
        if (targetStatus != null) {
            sb.append(", targetStatus=").append(targetStatus);
        }
        sb.append("]");
        return sb.toString();
    }
}
