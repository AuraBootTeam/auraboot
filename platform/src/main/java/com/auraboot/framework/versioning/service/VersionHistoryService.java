package com.auraboot.framework.versioning.service;

import com.auraboot.framework.versioning.dto.DesignVersionDTO;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Unified version history service for all designer types.
 * Provides version snapshot, history listing, and rollback capabilities.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
public interface VersionHistoryService {

    /**
     * Record a version snapshot for a resource.
     *
     * @param resourceType designer type (PAGE, DASHBOARD, BPMN, REPORT)
     * @param resourceId PID of the resource
     * @param operation operation type (CREATE, UPDATE, PUBLISH, etc.)
     * @param description optional change description
     * @return the created version entry
     */
    DesignVersionDTO recordVersion(String resourceType, String resourceId,
                                    String operation, String description);

    /**
     * Record a version snapshot with an explicit snapshot payload
     * (useful when the resource has already been modified and we need to
     * save the state before the modification).
     *
     * @param resourceType designer type
     * @param resourceId PID of the resource
     * @param snapshot explicit snapshot to store
     * @param operation operation type
     * @param description optional change description
     * @return the created version entry
     */
    DesignVersionDTO recordVersionWithSnapshot(String resourceType, String resourceId,
                                                JsonNode snapshot, String operation,
                                                String description);

    /**
     * Get version history for a resource (without snapshots).
     *
     * @param resourceType designer type
     * @param resourceId PID of the resource
     * @return list of version entries, ordered by operation_at DESC
     */
    List<DesignVersionDTO> getHistory(String resourceType, String resourceId);

    /**
     * Get a specific version entry with its full snapshot.
     *
     * @param versionPid PID of the version entry
     * @return version entry with snapshot, or null if not found
     */
    DesignVersionDTO getVersion(String versionPid);

    /**
     * Rollback a resource to a specific version.
     * Creates a ROLLBACK version entry and applies the snapshot.
     *
     * @param resourceType designer type
     * @param resourceId PID of the resource
     * @param versionPid PID of the version to rollback to
     * @return the rollback version entry
     */
    DesignVersionDTO rollback(String resourceType, String resourceId, String versionPid);

    /**
     * Get the total number of versions for a resource.
     */
    int countVersions(String resourceType, String resourceId);

    /**
     * Clean up old versions, keeping only the latest N entries.
     *
     * @param resourceType designer type
     * @param resourceId PID of the resource
     * @param keepCount number of recent versions to keep
     * @return number of deleted entries
     */
    int cleanupOldVersions(String resourceType, String resourceId, int keepCount);
}
