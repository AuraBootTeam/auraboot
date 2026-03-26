package com.auraboot.framework.versioning.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.versioning.VersionableResource;
import com.auraboot.framework.versioning.dto.DesignVersionDTO;
import com.auraboot.framework.versioning.entity.DesignVersionHistory;
import com.auraboot.framework.versioning.mapper.DesignVersionHistoryMapper;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Unified version history service implementation.
 * Delegates snapshot creation/application to type-specific VersionableResource strategies.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class VersionHistoryServiceImpl implements VersionHistoryService {

    private final DesignVersionHistoryMapper versionHistoryMapper;
    private final Map<String, VersionableResource> resourceStrategies;

    /**
     * Spring auto-collects all VersionableResource beans into a map keyed by bean name.
     * We index them by resourceType for lookup.
     */
    private VersionableResource getStrategy(String resourceType) {
        return resourceStrategies.values().stream()
                .filter(s -> s.getResourceType().equals(resourceType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No VersionableResource strategy registered for type: " + resourceType));
    }

    @Override
    public DesignVersionDTO recordVersion(String resourceType, String resourceId,
                                           String operation, String description) {
        VersionableResource strategy = getStrategy(resourceType);
        JsonNode snapshot = strategy.createSnapshot(resourceId);
        return recordVersionWithSnapshot(resourceType, resourceId, snapshot, operation, description);
    }

    @Override
    public DesignVersionDTO recordVersionWithSnapshot(String resourceType, String resourceId,
                                                       JsonNode snapshot, String operation,
                                                       String description) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userPid = MetaContext.getCurrentUserPid();

        // Determine version label (auto-increment count)
        int count = versionHistoryMapper.countByResource(tenantId, resourceType, resourceId);
        String version = String.valueOf(count + 1);

        // Find latest version PID for parent reference
        String parentVersionId = null;
        List<DesignVersionHistory> latest = versionHistoryMapper.findLatestVersions(
                tenantId, resourceType, resourceId, 1);
        if (!latest.isEmpty()) {
            parentVersionId = latest.get(0).getPid();
        }

        DesignVersionHistory history = new DesignVersionHistory();
        history.setPid(UniqueIdGenerator.generate());
        history.setTenantId(tenantId);
        history.setResourceType(resourceType);
        history.setResourceId(resourceId);
        history.setVersion(version);
        history.setSchemaSnapshot(snapshot);
        history.setOperation(operation);
        history.setOperationBy(userPid);
        history.setOperationAt(Instant.now());
        history.setDescription(description);
        history.setParentVersionId(parentVersionId);
        history.setCreatedAt(Instant.now());

        versionHistoryMapper.insertVersion(history);

        log.info("Recorded version {} for {} {}: operation={}",
                version, resourceType, resourceId, operation);

        return toDTO(history, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DesignVersionDTO> getHistory(String resourceType, String resourceId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DesignVersionHistory> versions = versionHistoryMapper.findByResource(
                tenantId, resourceType, resourceId);

        return versions.stream()
                .map(v -> toDTO(v, false))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public DesignVersionDTO getVersion(String versionPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        DesignVersionHistory version = versionHistoryMapper.findByPid(tenantId, versionPid);
        if (version == null) {
            return null;
        }
        return toDTO(version, true);
    }

    @Override
    public DesignVersionDTO rollback(String resourceType, String resourceId, String versionPid) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Get the target version
        DesignVersionHistory targetVersion = versionHistoryMapper.findByPid(tenantId, versionPid);
        if (targetVersion == null) {
            throw new IllegalArgumentException("Version not found: " + versionPid);
        }
        if (!targetVersion.getResourceType().equals(resourceType)
                || !targetVersion.getResourceId().equals(resourceId)) {
            throw new IllegalArgumentException("Version does not belong to the specified resource");
        }

        VersionableResource strategy = getStrategy(resourceType);

        // Record current state as a backup before rollback
        JsonNode currentSnapshot = strategy.createSnapshot(resourceId);
        recordVersionWithSnapshot(resourceType, resourceId, currentSnapshot,
                "backup_before_rollback", "Auto-backup before rollback to version " + targetVersion.getVersion());

        // Apply the target version's snapshot
        strategy.applySnapshot(resourceId, targetVersion.getSchemaSnapshot());

        // Record the rollback operation
        DesignVersionDTO rollbackEntry = recordVersionWithSnapshot(
                resourceType, resourceId, targetVersion.getSchemaSnapshot(),
                "rollback", "Rolled back to version " + targetVersion.getVersion());

        // Notify strategy
        strategy.onRollback(resourceId, targetVersion.getSchemaSnapshot());

        log.info("Rolled back {} {} to version {}",
                resourceType, resourceId, targetVersion.getVersion());

        return rollbackEntry;
    }

    @Override
    @Transactional(readOnly = true)
    public int countVersions(String resourceType, String resourceId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return versionHistoryMapper.countByResource(tenantId, resourceType, resourceId);
    }

    @Override
    public int cleanupOldVersions(String resourceType, String resourceId, int keepCount) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int deleted = versionHistoryMapper.deleteOldVersions(tenantId, resourceType, resourceId, keepCount);
        if (deleted > 0) {
            log.info("Cleaned up {} old versions for {} {}", deleted, resourceType, resourceId);
        }
        return deleted;
    }

    private DesignVersionDTO toDTO(DesignVersionHistory entity, boolean includeSnapshot) {
        return DesignVersionDTO.builder()
                .pid(entity.getPid())
                .resourceType(entity.getResourceType())
                .resourceId(entity.getResourceId())
                .version(entity.getVersion())
                .operation(entity.getOperation())
                .operationBy(entity.getOperationBy())
                .operationAt(entity.getOperationAt())
                .description(entity.getDescription())
                .parentVersionId(entity.getParentVersionId())
                .schemaSnapshot(includeSnapshot ? entity.getSchemaSnapshot() : null)
                .build();
    }
}
