package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Context object passed to each PluginValidator.
 * Contains both the manifest being imported and the current tenant state.
 */
@Data
@Builder
public class PluginValidationContext {

    /** Plugin ID being imported. */
    private String pluginId;

    /** Plugin namespace. */
    private String namespace;

    /** The full manifest being validated. */
    private PluginManifestExtended manifest;

    /** Model codes that already exist in the tenant (installed by other plugins or manually). */
    private Set<String> installedModelCodes;

    /** Field codes that already exist in the tenant. */
    private Set<String> installedFieldCodes;

    /** Permission codes that already exist in the tenant. */
    private Set<String> installedPermissionCodes;

    /** Command codes that already exist in the tenant. */
    private Set<String> installedCommandCodes;

    /** Named query codes that already exist in the tenant. */
    private Set<String> installedNamedQueryCodes;

    /** Plugin IDs that are installed in the tenant (for dependency graph). */
    private Set<String> installedPluginIds;

    /** Map of installed plugin ID → their dependency plugin IDs (for cycle detection). */
    private java.util.Map<String, List<String>> installedPluginDependencies;
}
