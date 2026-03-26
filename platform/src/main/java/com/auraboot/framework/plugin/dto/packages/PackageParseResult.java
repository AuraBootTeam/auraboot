package com.auraboot.framework.plugin.dto.packages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing a unified plugin package.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageParseResult {

    /**
     * Unique operation identifier for this parse/install session.
     */
    private String packageId;

    /**
     * Whether the package was parsed successfully.
     */
    private boolean success;

    /**
     * Error message if parsing failed.
     */
    private String error;

    /**
     * Validation errors.
     */
    private List<String> validationErrors;

    /**
     * Parsed manifest.
     */
    private PackageManifest manifest;

    /**
     * Path to extracted package directory.
     */
    private String extractedPath;

    // ========== Component Detection ==========

    /**
     * Detected components in the package.
     */
    private DetectedComponents detectedComponents;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetectedComponents {
        /**
         * Whether config component was detected.
         */
        private boolean hasConfig;

        /**
         * Config path if detected.
         */
        private String configPath;

        /**
         * Resource counts for config component.
         */
        private Map<String, Integer> configResourceCounts;

        /**
         * Whether backend component was detected.
         */
        private boolean hasBackend;

        /**
         * Backend JAR path if detected.
         */
        private String backendJarPath;

        /**
         * Backend plugin info if detected.
         */
        private BackendPluginInfo backendPluginInfo;

        /**
         * Whether frontend component was detected.
         */
        private boolean hasFrontend;

        /**
         * Frontend path if detected.
         */
        private String frontendPath;

        /**
         * Frontend manifest info if detected.
         */
        private FrontendManifestInfo frontendManifestInfo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BackendPluginInfo {
        private String pluginId;
        private String pluginClass;
        private String version;
        private String provider;
        private List<String> dependencies;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrontendManifestInfo {
        private String remoteEntryPath;
        private Map<String, String> exposedModules;
        private List<String> slotIds;
        private List<String> routePaths;
    }

    // ========== Conflict Detection ==========

    /**
     * Conflicts with existing resources.
     */
    private List<ResourceConflict> conflicts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceConflict {
        /**
         * Type of resource (MODEL, FIELD, etc.).
         */
        private String resourceType;

        /**
         * Resource code.
         */
        private String resourceCode;

        /**
         * Existing owner plugin ID.
         */
        private String existingPluginId;

        /**
         * Conflict description.
         */
        private String description;

        /**
         * Whether this conflict can be resolved by upgrade.
         */
        private boolean canUpgrade;
    }

    // ========== Factory Methods ==========

    public static PackageParseResult success(String packageId, PackageManifest manifest, String extractedPath) {
        return PackageParseResult.builder()
                .packageId(packageId)
                .success(true)
                .manifest(manifest)
                .extractedPath(extractedPath)
                .build();
    }

    public static PackageParseResult failure(String packageId, String error) {
        return PackageParseResult.builder()
                .packageId(packageId)
                .success(false)
                .error(error)
                .build();
    }

    public static PackageParseResult validationFailure(String packageId, List<String> validationErrors) {
        return PackageParseResult.builder()
                .packageId(packageId)
                .success(false)
                .error("Validation failed")
                .validationErrors(validationErrors)
                .build();
    }
}
