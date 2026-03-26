package com.auraboot.framework.plugin.dto.packages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Plugin package history DTO for API responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageHistoryDTO {

    /**
     * History record PID.
     */
    private String pid;

    /**
     * Plugin PID (after successful installation).
     */
    private String pluginPid;

    /**
     * Plugin ID from manifest.
     */
    private String pluginId;

    /**
     * Plugin namespace.
     */
    private String namespace;

    /**
     * Plugin version.
     */
    private String version;

    /**
     * Display name.
     */
    private String displayName;

    // ========== Source ==========

    /**
     * Source type: UPLOAD, PATH, URL.
     */
    private String sourceType;

    /**
     * Source name (filename or URL).
     */
    private String sourceName;

    // ========== Component Status ==========

    /**
     * Config component enabled.
     */
    private boolean configEnabled;

    /**
     * Config component status.
     */
    private String configStatus;

    /**
     * Config resource counts.
     */
    private Map<String, Integer> configResourceCounts;

    /**
     * Backend component enabled.
     */
    private boolean backendEnabled;

    /**
     * Backend component status.
     */
    private String backendStatus;

    /**
     * Frontend component enabled.
     */
    private boolean frontendEnabled;

    /**
     * Frontend component status.
     */
    private String frontendStatus;

    /**
     * Frontend remote URL.
     */
    private String frontendRemoteUrl;

    // ========== Overall Status ==========

    /**
     * Overall status.
     */
    private String status;

    /**
     * Error message if failed.
     */
    private String errorMessage;

    /**
     * Whether this can be rolled back.
     */
    private boolean canRollback;

    // ========== Timestamps ==========

    /**
     * Operation start time.
     */
    private Instant startedAt;

    /**
     * Operation completion time.
     */
    private Instant completedAt;

    /**
     * Record creation time.
     */
    private Instant createdAt;

    /**
     * User who initiated the operation.
     */
    private Long createdBy;
}
