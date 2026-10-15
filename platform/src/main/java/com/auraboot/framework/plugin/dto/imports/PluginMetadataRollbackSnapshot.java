package com.auraboot.framework.plugin.dto.imports;

import com.auraboot.framework.plugin.dto.PluginManifest;

/** Persisted metadata identity for guarded public publication import rollback. */
public record PluginMetadataRollbackSnapshot(int schemaVersion, Long tenantId, String pluginPid,
                                             Metadata before, Metadata after) {
    public record Metadata(String pluginId, String namespace, String version, String displayName,
                           String description, PluginManifest manifest) {}
}
