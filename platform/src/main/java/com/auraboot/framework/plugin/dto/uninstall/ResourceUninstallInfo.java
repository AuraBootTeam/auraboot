package com.auraboot.framework.plugin.dto.uninstall;

import com.auraboot.framework.plugin.dto.imports.OwnershipType;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Information about a single resource in the uninstall preview.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceUninstallInfo {

    /**
     * Resource tracking PID.
     */
    private String pid;

    /**
     * Type of resource.
     */
    private ResourceType type;

    /**
     * Resource code (identifier).
     */
    private String code;

    /**
     * Resource display name.
     */
    private String name;

    /**
     * Current ownership type.
     */
    private OwnershipType ownershipType;

    /**
     * Whether the resource has been modified by user.
     */
    private boolean modified;

    /**
     * Whether this resource has been claimed by user (detached from plugin).
     */
    private boolean claimed;

    /**
     * List of differences if modified.
     */
    private List<ResourceDiff> diffs;

    /**
     * Default decision based on ownership and modification status.
     */
    private UninstallDecision suggestedDecision;
}
