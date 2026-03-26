package com.auraboot.framework.plugin.service;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tracks resources imported during a single plugin import transaction.
 * Used for rollback on failure — resources are recorded in insertion order
 * and rolled back in reverse order.
 */
@Data
public class PluginImportContext {

    private final String pluginCode;

    /**
     * IDs of schemas inserted during this import, in insertion order.
     */
    private final List<Long> schemaIds = new ArrayList<>();

    /**
     * IDs of permissions inserted during this import, in insertion order.
     */
    private final List<Long> permissionIds = new ArrayList<>();

    /**
     * IDs of menus inserted during this import, in insertion order.
     */
    private final List<Long> menuIds = new ArrayList<>();

    public PluginImportContext(String pluginCode) {
        this.pluginCode = pluginCode;
    }

    /**
     * Build the imported_resources JSONB payload for the import log.
     */
    public List<Map<String, Object>> toResourceList() {
        List<Map<String, Object>> resources = new ArrayList<>();

        for (Long id : schemaIds) {
            resources.add(Map.of("type", "schema", "id", id));
        }
        for (Long id : permissionIds) {
            resources.add(Map.of("type", "permission", "id", id));
        }
        for (Long id : menuIds) {
            resources.add(Map.of("type", "menu", "id", id));
        }

        return resources;
    }

    public int totalImported() {
        return schemaIds.size() + permissionIds.size() + menuIds.size();
    }
}
