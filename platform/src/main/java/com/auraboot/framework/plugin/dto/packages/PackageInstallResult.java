package com.auraboot.framework.plugin.dto.packages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of package installation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageInstallResult {

    /**
     * Package operation ID.
     */
    private String packageId;

    /**
     * Whether the installation was successful.
     */
    private boolean success;

    /**
     * Overall error message if failed.
     */
    private String error;

    /**
     * Plugin PID after successful installation.
     */
    private String pluginPid;

    /**
     * Plugin ID from manifest.
     */
    private String pluginId;

    /**
     * Plugin version.
     */
    private String version;

    // ========== Component Results ==========

    /**
     * Config installation result.
     */
    private ComponentResult configResult;

    /**
     * Backend installation result.
     */
    private ComponentResult backendResult;

    /**
     * Frontend installation result.
     */
    private ComponentResult frontendResult;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComponentResult {
        /**
         * Component status.
         */
        private ComponentStatus status;

        /**
         * Error message if failed.
         */
        private String error;

        /**
         * Stack trace for debugging.
         */
        private String stackTrace;

        /**
         * Resource counts (for config component).
         */
        private Map<String, Integer> resourceCounts;

        /**
         * Created resource PIDs (for rollback).
         */
        private List<String> createdResourcePids;

        /**
         * Backend plugin ID (for backend component).
         */
        private String backendPluginId;

        /**
         * Backend plugin state (for backend component).
         */
        private String backendPluginState;

        /**
         * Frontend remote URL (for frontend component).
         */
        private String frontendRemoteUrl;

        /**
         * Deployed asset paths (for frontend component).
         */
        private List<String> deployedAssets;
    }

    /**
     * Component status.
     */
    public enum ComponentStatus {
        SUCCESS("success"),
        FAILED("failed"),
        SKIPPED("skipped"),
        ROLLED_BACK("rolled_back");

        private final String code;

        ComponentStatus(String code) {
            this.code = code;
        }

        /**
         * Returns the lowercase database value.
         */
        public String code() {
            return code;
        }

        /**
         * Parse from database value (case-insensitive).
         */
        public static ComponentStatus fromCode(String code) {
            if (code == null) return null;
            for (ComponentStatus s : values()) {
                if (s.code.equalsIgnoreCase(code)) return s;
            }
            return valueOf(code.toUpperCase());
        }
    }

    // ========== Rollback Info ==========

    /**
     * Whether this installation can be rolled back.
     */
    private boolean canRollback;

    /**
     * Rollback data for potential rollback.
     */
    private Map<String, Object> rollbackData;

    // ========== Timing ==========

    /**
     * Installation start time.
     */
    private Instant startedAt;

    /**
     * Installation completion time.
     */
    private Instant completedAt;

    /**
     * Duration in milliseconds.
     */
    private Long durationMs;

    // ========== Factory Methods ==========

    public static PackageInstallResult success(String packageId, String pluginPid, String pluginId, String version) {
        return PackageInstallResult.builder()
                .packageId(packageId)
                .success(true)
                .pluginPid(pluginPid)
                .pluginId(pluginId)
                .version(version)
                .canRollback(true)
                .build();
    }

    public static PackageInstallResult failure(String packageId, String error) {
        return PackageInstallResult.builder()
                .packageId(packageId)
                .success(false)
                .error(error)
                .build();
    }

    public static PackageInstallResult partialSuccess(String packageId, String pluginPid, String error) {
        return PackageInstallResult.builder()
                .packageId(packageId)
                .success(false)
                .pluginPid(pluginPid)
                .error(error)
                .canRollback(true)
                .build();
    }
}
