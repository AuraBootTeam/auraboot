package com.auraboot.framework.plugin.dto.packages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Configuration for a component in the unified plugin package.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageComponentConfig {

    /**
     * Whether this component is enabled.
     */
    private Boolean enabled;

    /**
     * Path to the component within the package.
     */
    private String path;

    // ========== Config Component ==========

    /**
     * For config component: list of resource directories.
     */
    private Map<String, String> resourceDirs;

    // ========== Backend Component ==========

    /**
     * For backend component: main plugin class name.
     */
    private String className;

    /**
     * For backend component: plugin ID (PF4J descriptor).
     */
    private String pluginId;

    /**
     * For backend component: plugin version.
     */
    private String pluginVersion;

    /**
     * For backend component: required dependencies.
     */
    private java.util.List<String> dependencies;

    // ========== Frontend Component ==========

    /**
     * For frontend component: remote entry filename.
     */
    private String remoteEntry;

    /**
     * For frontend component: exposed modules.
     */
    private Map<String, String> exposedModules;

    /**
     * For frontend component: slot contributions.
     */
    private java.util.List<SlotContribution> slots;

    /**
     * For frontend component: route contributions.
     */
    private java.util.List<RouteContribution> routes;

    /**
     * Slot contribution configuration.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlotContribution {
        private String slotId;
        private String componentName;
        private Integer priority;
        private Map<String, Object> props;
    }

    /**
     * Route contribution configuration.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteContribution {
        private String path;
        private String componentName;
        private String menuCode;
        private Map<String, Object> meta;
    }
}
