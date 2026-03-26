package com.auraboot.framework.plugin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Plugin information DTO for API responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginInfo {

    /**
     * Business unique identifier (PID).
     */
    private String pid;

    /**
     * Plugin unique identifier.
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
     * Human-readable display name.
     */
    private String displayName;

    /**
     * Plugin description.
     */
    private String description;

    /**
     * Plugin author.
     */
    private String author;

    /**
     * Current plugin status.
     */
    private PluginStatus status;

    /**
     * Installation timestamp.
     */
    private Instant installedAt;

    /**
     * Last enabled timestamp.
     */
    private Instant enabledAt;

    /**
     * Last disabled timestamp.
     */
    private Instant disabledAt;

    /**
     * Plugin runtime settings.
     */
    private Map<String, Object> settings;

    /**
     * Full manifest data.
     */
    private PluginManifest manifest;

    /**
     * Check if plugin is currently enabled.
     */
    public boolean isEnabled() {
        return status == PluginStatus.ENABLED;
    }

    /**
     * Check if plugin is in a failed state.
     */
    public boolean isFailed() {
        return status == PluginStatus.FAILED;
    }
}
