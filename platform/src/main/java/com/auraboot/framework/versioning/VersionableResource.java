package com.auraboot.framework.versioning;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Strategy interface for resources that support version management.
 * Each designer type (Dashboard, Page, BPMN, Report) implements this
 * to integrate with the unified version history layer.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
public interface VersionableResource {

    /**
     * The resource type identifier (e.g. "page", "dashboard", "bpmn", "report")
     */
    String getResourceType();

    /**
     * Create a JSONB snapshot of the current resource state.
     *
     * @param resourceId PID of the resource
     * @return full snapshot as JsonNode
     */
    JsonNode createSnapshot(String resourceId);

    /**
     * Apply a previously saved snapshot to restore resource state.
     * This is called during rollback operations.
     *
     * @param resourceId PID of the resource
     * @param snapshot the snapshot to apply
     */
    void applySnapshot(String resourceId, JsonNode snapshot);

    /**
     * Hook called after a publish operation is recorded.
     * Designers can override this for type-specific logic
     * (e.g. BPMN deploys to SmartEngine).
     *
     * @param resourceId PID of the resource
     */
    default void onPublish(String resourceId) {
        // No-op by default
    }

    /**
     * Hook called after a rollback operation is performed.
     *
     * @param resourceId PID of the resource
     * @param snapshot the snapshot that was applied
     */
    default void onRollback(String resourceId, JsonNode snapshot) {
        // No-op by default
    }
}
