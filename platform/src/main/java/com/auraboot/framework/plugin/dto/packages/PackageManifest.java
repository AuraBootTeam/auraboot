package com.auraboot.framework.plugin.dto.packages;

import com.auraboot.framework.plugin.dto.imports.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Unified plugin package manifest (plugin.json).
 * Combines metadata from DSL import, PF4J backend, and Module Federation frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageManifest {

    // ========== Core Metadata ==========

    /**
     * Unique plugin identifier (e.g., "com.example.my-plugin").
     */
    private String pluginId;

    /**
     * Plugin namespace for resource isolation.
     */
    private String namespace;

    /**
     * Plugin version in semver format.
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
     * Plugin author or organization.
     */
    private String author;

    /**
     * Minimum required platform version.
     */
    private String minPlatformVersion;

    // ========== Component Configurations ==========

    /**
     * Component configurations (config, backend, frontend).
     */
    private PackageComponents components;

    // ========== DSL Configuration Resources ==========

    /**
     * Dictionary definitions.
     */
    private List<DictDefinitionDTO> dicts;

    /**
     * Field definitions.
     */
    private List<FieldDefinitionDTO> fields;

    /**
     * Model definitions.
     */
    private List<ModelDefinitionDTO> models;

    /**
     * Model-field bindings.
     */
    private List<ModelFieldBindingDTO> modelFieldBindings;

    /**
     * Command definitions.
     */
    private List<CommandDefinitionDTO> commands;

    /**
     * Permission definitions.
     */
    private List<PermissionDefinitionDTO> permissions;

    /**
     * Role definitions.
     */
    private List<RoleDefinitionDTO> roles;

    /**
     * Menu definitions.
     */
    private List<MenuDefinitionDTO> menus;

    /**
     * Page schema definitions.
     */
    private List<PageSchemaDTO> pages;

    /**
     * Process definitions.
     */
    private List<ProcessDefinitionDTO> processes;

    /**
     * Resource directories mapping (e.g., "models" -> "config/models.json").
     * Used to locate resource files within the package.
     */
    private Map<String, String> resourceDirs;

    // ========== Additional Metadata ==========

    /**
     * Plugin-specific configuration schema.
     */
    private Map<String, Object> configSchema;

    /**
     * Default configuration values.
     */
    private Map<String, Object> defaultConfig;

    /**
     * Additional metadata.
     */
    private Map<String, Object> metadata;

    // ========== Component Configurations Container ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackageComponents {
        /**
         * Configuration (DSL) component.
         */
        private PackageComponentConfig config;

        /**
         * Backend (PF4J JAR) component.
         */
        private PackageComponentConfig backend;

        /**
         * Frontend (Module Federation) component.
         */
        private PackageComponentConfig frontend;
    }

    // ========== Validation ==========

    @JsonIgnore
    public boolean isValid() {
        return pluginId != null && !pluginId.isBlank()
                && namespace != null && !namespace.isBlank()
                && version != null && !version.isBlank();
    }

    @JsonIgnore
    public boolean hasConfigComponent() {
        return components != null && components.getConfig() != null
                && Boolean.TRUE.equals(components.getConfig().getEnabled());
    }

    @JsonIgnore
    public boolean hasBackendComponent() {
        return components != null && components.getBackend() != null
                && Boolean.TRUE.equals(components.getBackend().getEnabled());
    }

    @JsonIgnore
    public boolean hasFrontendComponent() {
        return components != null && components.getFrontend() != null
                && Boolean.TRUE.equals(components.getFrontend().getEnabled());
    }

    @JsonIgnore
    public boolean hasAnyResources() {
        return (dicts != null && !dicts.isEmpty())
                || (fields != null && !fields.isEmpty())
                || (models != null && !models.isEmpty())
                || (modelFieldBindings != null && !modelFieldBindings.isEmpty())
                || (commands != null && !commands.isEmpty())
                || (permissions != null && !permissions.isEmpty())
                || (roles != null && !roles.isEmpty())
                || (menus != null && !menus.isEmpty())
                || (pages != null && !pages.isEmpty())
                || (processes != null && !processes.isEmpty());
    }
}
