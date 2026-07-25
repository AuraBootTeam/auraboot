package com.auraboot.framework.plugin.dto;

import java.time.Instant;

/**
 * Service-layer view of a plugin-managed resource and its owning plugin.
 */
public record PluginResourceOwner(
        String pluginId,
        String pluginName,
        String pluginVersion,
        String ownershipType,
        boolean userModified,
        Instant userModifiedAt,
        Instant importedAt
) {
}
