package com.auraboot.framework.plugin.dto.packages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Plugin package status DTO for API responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageStatusDTO {

    /**
     * Plugin PID.
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
     * Plugin version.
     */
    private String version;

    /**
     * Display name.
     */
    private String displayName;

    /**
     * Overall plugin status.
     */
    private String status;

    // ========== Component Status ==========

    /**
     * Whether plugin has config component.
     */
    private boolean hasConfig;

    /**
     * Config component status.
     */
    private String configStatus;

    /**
     * Whether plugin has backend component.
     */
    private boolean hasBackend;

    /**
     * Backend component status.
     */
    private String backendStatus;

    /**
     * Backend PF4J plugin ID.
     */
    private String backendPluginId;

    /**
     * Whether plugin has frontend component.
     */
    private boolean hasFrontend;

    /**
     * Frontend component status.
     */
    private String frontendStatus;

    /**
     * Frontend remote URL for Module Federation.
     */
    private String frontendRemoteUrl;

    // ========== Errors ==========

    /**
     * Backend error if any.
     */
    private String backendError;

    /**
     * Frontend error if any.
     */
    private String frontendError;

    // ========== Resource Counts ==========

    /**
     * Resource counts by type.
     */
    private Map<String, Integer> resourceCounts;

    // ========== Timestamps ==========

    /**
     * Installation time.
     */
    private Instant installedAt;

    /**
     * Last enabled time.
     */
    private Instant enabledAt;

    /**
     * Last update time.
     */
    private Instant updatedAt;
}
