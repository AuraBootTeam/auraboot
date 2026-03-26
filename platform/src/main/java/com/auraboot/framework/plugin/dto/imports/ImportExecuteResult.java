package com.auraboot.framework.plugin.dto.imports;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of import execution operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportExecuteResult {

    /**
     * Import ID for tracking.
     */
    private String importId;

    /**
     * Plugin PID after successful import.
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
     * Whether import was successful.
     */
    private boolean success;

    /**
     * Import status.
     */
    private ImportStatus status;

    /**
     * Error message if failed.
     */
    private String errorMessage;

    /**
     * Error details/stack trace.
     */
    private String errorDetail;

    /**
     * Warnings during import.
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * Resource counts by type and action.
     */
    @Builder.Default
    private Map<String, Map<String, Integer>> resourceCounts = new HashMap<>();

    /**
     * Created resource PIDs by type.
     */
    @Builder.Default
    private Map<String, List<String>> createdResources = new HashMap<>();

    /**
     * Import start time.
     */
    private Instant startedAt;

    /**
     * Import completion time.
     */
    private Instant completedAt;

    /**
     * Duration in milliseconds.
     */
    private Long durationMs;

    /**
     * Deployed process keys (if processes were auto-deployed).
     */
    @Builder.Default
    private List<String> deployedProcesses = new ArrayList<>();

    /**
     * Create a success result.
     */
    public static ImportExecuteResult success(String importId, String pluginPid, String pluginId,
                                               String namespace, String version) {
        return ImportExecuteResult.builder()
                .importId(importId)
                .pluginPid(pluginPid)
                .pluginId(pluginId)
                .namespace(namespace)
                .version(version)
                .success(true)
                .status(ImportStatus.SUCCESS)
                .build();
    }

    /**
     * Create a failure result.
     */
    public static ImportExecuteResult failure(String importId, String pluginId,
                                               String namespace, String version,
                                               String errorMessage, String errorDetail) {
        return ImportExecuteResult.builder()
                .importId(importId)
                .pluginId(pluginId)
                .namespace(namespace)
                .version(version)
                .success(false)
                .status(ImportStatus.FAILED)
                .errorMessage(errorMessage)
                .errorDetail(errorDetail)
                .build();
    }

    /**
     * Add a warning.
     */
    public void addWarning(String warning) {
        if (warnings == null) {
            warnings = new ArrayList<>();
        }
        warnings.add(warning);
    }

    /**
     * Increment resource count.
     */
    public void incrementResourceCount(ResourceType type, ResourceAction action) {
        if (resourceCounts == null) {
            resourceCounts = new HashMap<>();
        }
        resourceCounts
                .computeIfAbsent(type.name(), k -> new HashMap<>())
                .merge(action.name(), 1, Integer::sum);
    }

    /**
     * Add created resource.
     */
    public void addCreatedResource(ResourceType type, String pid) {
        if (createdResources == null) {
            createdResources = new HashMap<>();
        }
        createdResources
                .computeIfAbsent(type.name(), k -> new ArrayList<>())
                .add(pid);
    }

    /**
     * Get total resource count.
     */
    public int getTotalResourceCount() {
        if (resourceCounts == null) {
            return 0;
        }
        return resourceCounts.values().stream()
                .flatMap(m -> m.values().stream())
                .mapToInt(Integer::intValue)
                .sum();
    }

    /**
     * Calculate duration.
     */
    public void calculateDuration() {
        if (startedAt != null && completedAt != null) {
            durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
    }
}
