package com.auraboot.framework.plugin.dto.uninstall;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Result of plugin uninstall execution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UninstallResult {

    /**
     * Whether uninstall was successful.
     */
    private boolean success;

    /**
     * Plugin PID that was uninstalled.
     */
    private String pluginPid;

    /**
     * Plugin ID.
     */
    private String pluginId;

    /**
     * Number of resources deleted.
     */
    private int deletedCount;

    /**
     * Number of resources detached (ownership transferred to user).
     */
    private int detachedCount;

    /**
     * Number of resources kept (already user-claimed).
     */
    private int keptCount;

    /**
     * List of deleted resource codes.
     */
    private List<String> deletedResources;

    /**
     * List of detached resource codes.
     */
    private List<String> detachedResources;

    /**
     * Error message if failed.
     */
    private String errorMessage;

    /**
     * Timestamp of uninstall.
     */
    private Instant uninstalledAt;
}
