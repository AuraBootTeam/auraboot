package com.auraboot.framework.plugin.dto.uninstall;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Result of uninstall preview showing categorized resources.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UninstallPreviewResult {

    /**
     * Plugin PID being uninstalled.
     */
    private String pluginPid;

    /**
     * Plugin ID (e.g., com.example.billing).
     */
    private String pluginId;

    /**
     * Plugin display name.
     */
    private String pluginName;

    /**
     * Plugin version.
     */
    private String pluginVersion;

    /**
     * Resources that will be deleted (PLUGIN_OWNED or SHARED without modifications).
     */
    private List<ResourceUninstallInfo> willDelete;

    /**
     * Resources that need user decision (SHARED with modifications).
     */
    private List<ResourceUninstallInfo> needsDecision;

    /**
     * Resources that will be kept (USER_CLAIMED).
     */
    private List<ResourceUninstallInfo> willKeep;

    /**
     * Summary counts by resource type.
     */
    private Map<String, Integer> summaryCounts;

    /**
     * Whether there are resources that need user decision.
     */
    private boolean hasConflicts;

    /**
     * Total number of resources affected.
     */
    private int totalResources;
}
