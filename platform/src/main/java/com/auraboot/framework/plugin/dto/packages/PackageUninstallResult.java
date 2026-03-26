package com.auraboot.framework.plugin.dto.packages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of package uninstallation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageUninstallResult {

    /**
     * Plugin PID that was uninstalled.
     */
    private String pluginPid;

    /**
     * Plugin ID.
     */
    private String pluginId;

    /**
     * Whether the uninstallation was successful.
     */
    private boolean success;

    /**
     * Error message if failed.
     */
    private String error;

    // ========== Component Results ==========

    /**
     * Config uninstallation result.
     */
    private ComponentUninstallResult configResult;

    /**
     * Backend uninstallation result.
     */
    private ComponentUninstallResult backendResult;

    /**
     * Frontend uninstallation result.
     */
    private ComponentUninstallResult frontendResult;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComponentUninstallResult {
        /**
         * Component status.
         */
        private ComponentStatus status;

        /**
         * Error message if failed.
         */
        private String error;

        /**
         * Resources deleted.
         */
        private List<ResourceInfo> deletedResources;

        /**
         * Resources detached (ownership transferred to user).
         */
        private List<ResourceInfo> detachedResources;

        /**
         * Resources kept.
         */
        private List<ResourceInfo> keptResources;

        /**
         * Files removed.
         */
        private List<String> removedFiles;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceInfo {
        private String pid;
        private String type;
        private String code;
        private String name;
    }

    /**
     * Component status.
     */
    public enum ComponentStatus {
        SUCCESS,
        FAILED,
        SKIPPED,
        PARTIAL
    }

    // ========== Summary ==========

    /**
     * Resource counts by action.
     */
    private Map<String, Integer> resourceCounts;

    /**
     * Total files removed.
     */
    private int filesRemoved;

    // ========== Timing ==========

    /**
     * Uninstallation start time.
     */
    private Instant startedAt;

    /**
     * Uninstallation completion time.
     */
    private Instant completedAt;

    /**
     * Duration in milliseconds.
     */
    private Long durationMs;

    // ========== Factory Methods ==========

    public static PackageUninstallResult success(String pluginPid, String pluginId) {
        return PackageUninstallResult.builder()
                .pluginPid(pluginPid)
                .pluginId(pluginId)
                .success(true)
                .build();
    }

    public static PackageUninstallResult failure(String pluginPid, String pluginId, String error) {
        return PackageUninstallResult.builder()
                .pluginPid(pluginPid)
                .pluginId(pluginId)
                .success(false)
                .error(error)
                .build();
    }
}
