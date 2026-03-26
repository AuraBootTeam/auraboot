package com.auraboot.framework.plugin.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request payload for plugin import.
 * Contains plugin metadata and the resources to import (schemas, permissions, menus).
 */
@Data
public class PluginImportRequest {

    /**
     * Unique plugin identifier code
     */
    private String pluginCode;

    /**
     * Semantic version of the plugin
     */
    private String pluginVersion;

    /**
     * Schema definitions to import.
     * Each map should contain: type (String), rowId (String), data (Object)
     */
    private List<Map<String, Object>> schemas;

    /**
     * Permission definitions to import.
     * Each map should contain: code (String), name (String), module (String), type (String)
     */
    private List<Map<String, Object>> permissions;

    /**
     * Menu definitions to import.
     * Each map should contain: name (String), path (String), icon (String), permissionCode (String), orderNo (Integer)
     */
    private List<Map<String, Object>> menus;
}
