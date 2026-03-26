package com.auraboot.framework.plugin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a plugin lifecycle operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginOperationResult {

    /**
     * Whether the operation was successful.
     */
    private boolean success;

    /**
     * Plugin PID (if applicable).
     */
    private String pluginPid;

    /**
     * Plugin ID.
     */
    private String pluginId;

    /**
     * Plugin namespace.
     */
    private String namespace;

    /**
     * Operation that was performed.
     */
    private OperationType operation;

    /**
     * Previous status (before operation).
     */
    private PluginStatus previousStatus;

    /**
     * Current status (after operation).
     */
    private PluginStatus currentStatus;

    /**
     * Error message if operation failed.
     */
    private String errorMessage;

    /**
     * Detailed error information.
     */
    private String errorDetail;

    /**
     * Plugin operation types.
     */
    public enum OperationType {
        INSTALL,
        ENABLE,
        DISABLE,
        UNINSTALL,
        UPDATE_SETTINGS
    }

    /**
     * Create a successful result for install operation.
     */
    public static PluginOperationResult installSuccess(String pluginPid, String pluginId, String namespace) {
        return PluginOperationResult.builder()
                .success(true)
                .pluginPid(pluginPid)
                .pluginId(pluginId)
                .namespace(namespace)
                .operation(OperationType.INSTALL)
                .previousStatus(null)
                .currentStatus(PluginStatus.INSTALLED)
                .build();
    }

    /**
     * Create a successful result for enable operation.
     */
    public static PluginOperationResult enableSuccess(String pluginPid, String pluginId, String namespace) {
        return PluginOperationResult.builder()
                .success(true)
                .pluginPid(pluginPid)
                .pluginId(pluginId)
                .namespace(namespace)
                .operation(OperationType.ENABLE)
                .previousStatus(PluginStatus.INSTALLED)
                .currentStatus(PluginStatus.ENABLED)
                .build();
    }

    /**
     * Create a successful result for disable operation.
     */
    public static PluginOperationResult disableSuccess(String pluginPid, String pluginId, String namespace) {
        return PluginOperationResult.builder()
                .success(true)
                .pluginPid(pluginPid)
                .pluginId(pluginId)
                .namespace(namespace)
                .operation(OperationType.DISABLE)
                .previousStatus(PluginStatus.ENABLED)
                .currentStatus(PluginStatus.DISABLED)
                .build();
    }

    /**
     * Create a successful result for uninstall operation.
     */
    public static PluginOperationResult uninstallSuccess(String pluginPid, String pluginId, String namespace) {
        return PluginOperationResult.builder()
                .success(true)
                .pluginPid(pluginPid)
                .pluginId(pluginId)
                .namespace(namespace)
                .operation(OperationType.UNINSTALL)
                .currentStatus(null)
                .build();
    }

    /**
     * Create a failure result.
     */
    public static PluginOperationResult failure(OperationType operation, String pluginId, String namespace,
                                                 String errorMessage, String errorDetail) {
        return PluginOperationResult.builder()
                .success(false)
                .pluginId(pluginId)
                .namespace(namespace)
                .operation(operation)
                .errorMessage(errorMessage)
                .errorDetail(errorDetail)
                .build();
    }
}
