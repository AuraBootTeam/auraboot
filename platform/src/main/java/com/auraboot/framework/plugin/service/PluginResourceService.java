package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.dto.imports.OwnershipType;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.uninstall.*;
import com.auraboot.framework.plugin.entity.PluginResource;

import java.util.List;
import java.util.Map;

/**
 * Service for managing plugin resource ownership and lifecycle.
 */
public interface PluginResourceService {

    // ==================== Resource Query ====================

    /**
     * Find all resources for a plugin.
     */
    List<PluginResource> findByPluginPid(String pluginPid);

    /**
     * Find resource by tenant, type and code.
     */
    PluginResource findByTypeAndCode(Long tenantId, ResourceType type, String code);

    /**
     * Check if a resource exists and is managed by a plugin.
     */
    boolean isResourceManagedByPlugin(Long tenantId, ResourceType type, String code);

    /**
     * Get the plugin that manages a resource.
     */
    String getManagingPluginPid(Long tenantId, ResourceType type, String code);

    // ==================== Ownership Management ====================

    /**
     * Get ownership type for a resource.
     */
    OwnershipType getOwnershipType(Long tenantId, ResourceType type, String code);

    /**
     * Update ownership type for a resource.
     */
    void updateOwnershipType(Long tenantId, ResourceType type, String code, OwnershipType newType);

    /**
     * Mark a resource as modified by user.
     */
    void markAsUserModified(Long tenantId, ResourceType type, String code);

    /**
     * Transfer ownership to user (detach from plugin management).
     */
    void claimByUser(Long tenantId, ResourceType type, String code);

    /**
     * Check if a resource has been modified by user since import.
     */
    boolean isUserModified(Long tenantId, ResourceType type, String code);

    // ==================== Modification Detection ====================

    /**
     * Detect modifications by comparing import snapshot with current database state.
     * Returns list of field-level diffs.
     */
    List<ResourceDiff> detectModifications(Long tenantId, ResourceType type, String code);

    /**
     * Get current database state for a resource.
     */
    Map<String, Object> getCurrentDatabaseState(Long tenantId, ResourceType type, String code);

    /**
     * Compare two states and return differences.
     */
    List<ResourceDiff> compareStates(Map<String, Object> original, Map<String, Object> current);

    // ==================== Uninstall Operations ====================

    /**
     * Generate uninstall preview for a plugin.
     * Categorizes resources into: willDelete, needsDecision, willKeep
     */
    UninstallPreviewResult generateUninstallPreview(String pluginPid, Long tenantId);

    /**
     * Execute plugin uninstall with user decisions.
     */
    UninstallResult executeUninstall(String pluginPid, Long tenantId, UninstallRequest request);

    /**
     * Delete a single plugin resource (soft delete the actual resource).
     */
    void deleteResource(PluginResource resource);

    /**
     * Detach a resource from plugin (set ownership to USER_CLAIMED).
     */
    void detachResource(PluginResource resource);

    // ==================== Bulk Operations ====================

    /**
     * Get all modified resources for a plugin.
     */
    List<PluginResource> findModifiedResources(String pluginPid);

    /**
     * Get all user-claimed resources (originally from a plugin).
     */
    List<PluginResource> findUserClaimedResources(String pluginPid);

    /**
     * Count resources by ownership type for a plugin.
     */
    Map<OwnershipType, Integer> countByOwnershipType(String pluginPid);
}
