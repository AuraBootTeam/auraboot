package com.auraboot.framework.plugin.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plugin manifest containing metadata and configuration.
 * This is typically loaded from plugin.json or plugin.yaml.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PluginManifest {

    /**
     * Unique plugin identifier (e.g., "com.example.my-plugin").
     * Required.
     */
    private String pluginId;

    /**
     * Plugin namespace for resource isolation.
     * Must be unique within a tenant.
     * Required.
     */
    private String namespace;

    /**
     * Plugin version in semver format (e.g., "1.0.0").
     * Required.
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
     * Plugin homepage or documentation URL.
     */
    private String homepage;

    /**
     * DSL schema version used by this plugin's page schemas.
     * Defaults to 1 if not specified.
     */
    private Integer dslVersion;

    /**
     * Plugin type classification: config, hybrid, or solution.
     * Defaults to "config" if not specified.
     */
    private String pluginType;

    /**
     * Minimum required platform version.
     */
    private String minPlatformVersion;

    /**
     * Maximum supported platform version. If the current platform version exceeds this,
     * the plugin may be incompatible (returns WARN_NEWER).
     */
    private String maxPlatformVersion;

    /**
     * Structured dependency specifications with version constraints.
     * This is the internal representation — always populated during deserialization.
     */
    @JsonIgnore
    private List<PluginDependencySpec> dependencySpecs;

    /**
     * Plugin-specific configuration schema.
     */
    private Map<String, Object> configSchema;

    /**
     * Default configuration values.
     */
    private Map<String, Object> defaultConfig;

    /**
     * List of permissions required by this plugin.
     */
    private List<String> requiredPermissions;

    /**
     * List of model codes this plugin will create.
     */
    private List<String> providedModels;

    /**
     * List of command codes this plugin will register.
     */
    private List<String> providedCommands;

    /**
     * Plugin entry point class name (fully qualified).
     */
    private String entryPoint;

    /**
     * Capabilities this plugin provides to others.
     */
    private List<CapabilityDeclaration> provides;

    /**
     * Capabilities this plugin requires from other plugins.
     */
    private List<CapabilityRequirement> requires;

    /**
     * Additional metadata.
     */
    private Map<String, Object> metadata;

    /**
     * Distribution edition for marketplace/catalog flows, for example oss or enterprise.
     */
    private String edition;

    /**
     * Plugin IDs that can be upgraded to this plugin.
     */
    private List<String> upgradesFrom;

    /**
     * Plugin IDs replaced by this plugin.
     */
    private List<String> replaces;

    // ==================== dependencies dual-format support ====================

    /**
     * Getter for serialization — returns plain string list of plugin IDs.
     * This ensures backward-compatible JSON output.
     */
    @JsonGetter("dependencies")
    public List<String> getDependencies() {
        if (dependencySpecs == null) return null;
        return dependencySpecs.stream()
                .map(PluginDependencySpec::getPluginId)
                .toList();
    }

    /**
     * Setter for deserialization — handles both old and new dependency formats.
     * <p>
     * Old format: {@code ["com.auraboot.org-management"]}
     * New format: {@code [{"pluginId": "com.auraboot.org-management", "version": ">=1.0.0"}]}
     * Mixed: both strings and objects in the same array.
     * <p>
     * Jackson deserializes the JSON array into {@code List<Object>} where each element
     * is either a {@code String} (old format) or a {@code LinkedHashMap} (new object format).
     */
    @SuppressWarnings("unchecked")
    @JsonSetter("dependencies")
    public void setDependencies(List<Object> rawDeps) {
        if (rawDeps == null) {
            this.dependencySpecs = null;
            return;
        }
        this.dependencySpecs = new ArrayList<>();
        for (Object item : rawDeps) {
            if (item instanceof String pluginId) {
                dependencySpecs.add(new PluginDependencySpec(pluginId, "*"));
            } else if (item instanceof Map<?, ?> map) {
                String pluginId = map.get("pluginId") != null ? map.get("pluginId").toString() : null;
                String versionRange = map.get("version") != null ? map.get("version").toString() : "*";
                if (pluginId != null) {
                    dependencySpecs.add(new PluginDependencySpec(pluginId, versionRange));
                }
            }
        }
    }

    // ==================== Helper methods ====================

    /**
     * Get the effective DSL version (defaults to 1).
     */
    @JsonIgnore
    public int getEffectiveDslVersion() {
        return dslVersion != null ? dslVersion : 1;
    }

    /**
     * Get the effective plugin type (defaults to "config").
     */
    @JsonIgnore
    public String getEffectivePluginType() {
        return pluginType != null && !pluginType.isBlank() ? pluginType : "config";
    }

    /**
     * Get the resolved dependency specs (with version constraints).
     */
    @JsonIgnore
    public List<PluginDependencySpec> getEffectiveDependencySpecs() {
        if (dependencySpecs == null) return List.of();
        return dependencySpecs;
    }

    /**
     * Validate manifest has required fields.
     *
     * @return true if manifest is valid
     */
    @JsonIgnore
    public boolean isValid() {
        return pluginId != null && !pluginId.isBlank()
                && namespace != null && !namespace.isBlank()
                && version != null && !version.isBlank();
    }

    /**
     * Structured dependency specification with version constraint.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PluginDependencySpec {
        /**
         * Dependent plugin ID.
         */
        private String pluginId;

        /**
         * Version range constraint (e.g., ">=1.0.0", "^1.2.0", "*").
         */
        private String versionRange;
    }

    /**
     * A capability that this plugin provides to other plugins.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CapabilityDeclaration {
        /** Capability type: model, command, query, automation, api. */
        private String type;
        /** Capability code (e.g., crm_account, crm:create_lead). */
        private String code;
    }

    /**
     * A capability that this plugin requires from other plugins.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CapabilityRequirement {
        /** Required capability type: model, command, query, automation, api. */
        private String type;
        /** Required capability code. */
        private String code;
        /** If true, plugin works without this capability (degrades gracefully). */
        private boolean optional;
    }
}
