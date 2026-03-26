package com.auraboot.framework.plugin.dto.imports;

import com.auraboot.framework.plugin.validation.PluginValidationResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of import preview operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportPreviewResult {

    /**
     * Import ID for tracking.
     */
    private String importId;

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
     * Plugin display name.
     */
    private String displayName;

    /**
     * Whether this is a fresh install or upgrade.
     */
    private boolean isUpgrade;

    /**
     * Previous version if upgrade.
     */
    private String previousVersion;

    /**
     * Whether the preview passed validation.
     */
    private boolean valid;

    /**
     * Validation errors if not valid.
     */
    @Builder.Default
    private List<String> errors = new ArrayList<>();

    /**
     * Validation warnings.
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * Resource changes grouped by type.
     */
    @Builder.Default
    private Map<String, List<ResourceChange>> changes = new HashMap<>();

    /**
     * Summary counts by action.
     */
    @Builder.Default
    private Map<String, Integer> actionCounts = new HashMap<>();

    /**
     * Dependency analysis results.
     */
    private DependencyAnalysis dependencyAnalysis;

    /**
     * Conflict detection results.
     */
    @Builder.Default
    private List<ResourceConflict> conflicts = new ArrayList<>();

    /**
     * Extended validation result from the pre-flight pipeline (semantic + governance).
     */
    private PluginValidationResult validationResult;

    /**
     * Add an error.
     */
    public void addError(String error) {
        if (errors == null) {
            errors = new ArrayList<>();
        }
        errors.add(error);
        valid = false;
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
     * Add a resource change.
     */
    public void addChange(ResourceType type, ResourceChange change) {
        if (changes == null) {
            changes = new HashMap<>();
        }
        changes.computeIfAbsent(type.name(), k -> new ArrayList<>()).add(change);

        // Update action counts
        if (actionCounts == null) {
            actionCounts = new HashMap<>();
        }
        String action = change.getAction().name();
        actionCounts.merge(action, 1, Integer::sum);
    }

    /**
     * Get total change count.
     */
    public int getTotalChangeCount() {
        return actionCounts == null ? 0 : actionCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Resource change details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceChange {
        /**
         * Resource type.
         */
        private ResourceType resourceType;

        /**
         * Resource code.
         */
        private String resourceCode;

        /**
         * Resource name/description.
         */
        private String resourceName;

        /**
         * Action to be performed.
         */
        private ResourceAction action;

        /**
         * Field-level changes (for updates).
         */
        private List<FieldChange> fieldChanges;

        /**
         * Whether this resource has been modified by a user via Studio/API.
         * When true and using OVERWRITE_SAFE strategy, this resource will be skipped.
         */
        private boolean userModified;

        /**
         * When the user modification occurred.
         */
        private java.time.Instant userModifiedAt;

        /**
         * Additional details.
         */
        private Map<String, Object> details;
    }

    /**
     * Field-level change for updates.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldChange {
        private String fieldName;
        private Object oldValue;
        private Object newValue;
    }

    /**
     * Dependency analysis results.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DependencyAnalysis {
        /**
         * Required plugin dependencies.
         */
        private List<PluginDependency> pluginDependencies;

        /**
         * Required model references.
         */
        private List<String> requiredModels;

        /**
         * Required field references.
         */
        private List<String> requiredFields;

        /**
         * Required dict references.
         */
        private List<String> requiredDicts;

        /**
         * Missing dependencies.
         */
        private List<String> missingDependencies;

        /**
         * Whether all dependencies are satisfied.
         */
        private boolean satisfied;
    }

    /**
     * Plugin dependency information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PluginDependency {
        private String pluginId;
        private String requiredVersion;
        private String installedVersion;
        private boolean satisfied;
    }

    /**
     * Resource conflict information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceConflict {
        /**
         * Resource type.
         */
        private ResourceType resourceType;

        /**
         * Resource code.
         */
        private String resourceCode;

        /**
         * Conflict type: EXISTS, DIFFERENT_PLUGIN, VERSION_MISMATCH.
         */
        private String conflictType;

        /**
         * Existing resource owner plugin.
         */
        private String ownerPluginId;

        /**
         * Conflict description.
         */
        private String description;
    }
}
