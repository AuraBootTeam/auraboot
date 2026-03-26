package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.OwnershipType;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.uninstall.*;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.PluginResourceService;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of plugin resource service for ownership and lifecycle management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginResourceServiceImpl implements PluginResourceService {

    private final PluginResourceMapper resourceMapper;
    private final PluginRecordMapper pluginRecordMapper;
    private final MetaModelFieldBindingMapper bindingMapper;
    private final ObjectMapper objectMapper;
    // JdbcTemplate retained only for getCurrentDatabaseState() which requires dynamic table/column queries
    private final JdbcTemplate jdbcTemplate;

    // Fields to ignore when comparing states.
    // Superset of SystemFieldConstants.ALL_INFRASTRUCTURE — includes camelCase variants
    // and plugin-specific columns (plugin_pid, version, is_current).
    private static final Set<String> IGNORED_FIELDS = Set.of(
            "id", "pid", "tenant_id", "tenantId", "created_at", "createdAt",
            "updated_at", "updatedAt", "deleted_flag", "deletedFlag",
            "plugin_pid", "pluginPid", "version", "is_current", "isCurrent"
    );

    // ==================== Resource Query ====================

    @Override
    public List<PluginResource> findByPluginPid(String pluginPid) {
        return resourceMapper.findByPluginPid(pluginPid);
    }

    @Override
    public PluginResource findByTypeAndCode(Long tenantId, ResourceType type, String code) {
        return resourceMapper.findByTypeAndCode(tenantId, type.code(), code);
    }

    @Override
    public boolean isResourceManagedByPlugin(Long tenantId, ResourceType type, String code) {
        PluginResource resource = findByTypeAndCode(tenantId, type, code);
        return resource != null && resource.isManagedByPlugin();
    }

    @Override
    public String getManagingPluginPid(Long tenantId, ResourceType type, String code) {
        PluginResource resource = findByTypeAndCode(tenantId, type, code);
        if (resource != null && resource.isManagedByPlugin()) {
            return resource.getPluginPid();
        }
        return null;
    }

    // ==================== Ownership Management ====================

    @Override
    public OwnershipType getOwnershipType(Long tenantId, ResourceType type, String code) {
        PluginResource resource = findByTypeAndCode(tenantId, type, code);
        return resource != null ? resource.getOwnershipTypeEnum() : null;
    }

    @Override
    @Transactional
    public void updateOwnershipType(Long tenantId, ResourceType type, String code, OwnershipType newType) {
        PluginResource resource = findByTypeAndCode(tenantId, type, code);
        if (resource != null) {
            resource.setOwnershipTypeEnum(newType);
            resource.setUpdatedAt(Instant.now());
            resourceMapper.updateById(resource);
        }
    }

    @Override
    @Transactional
    public void markAsUserModified(Long tenantId, ResourceType type, String code) {
        PluginResource resource = findByTypeAndCode(tenantId, type, code);
        if (resource != null && !Boolean.TRUE.equals(resource.getUserModified())) {
            resource.markAsUserModified();
            resource.setUpdatedAt(Instant.now());
            resourceMapper.updateById(resource);
        }
    }

    @Override
    @Transactional
    public void claimByUser(Long tenantId, ResourceType type, String code) {
        PluginResource resource = findByTypeAndCode(tenantId, type, code);
        if (resource != null) {
            resource.claimByUser();
            resource.setUpdatedAt(Instant.now());
            resourceMapper.updateById(resource);
        }
    }

    @Override
    public boolean isUserModified(Long tenantId, ResourceType type, String code) {
        PluginResource resource = findByTypeAndCode(tenantId, type, code);
        return resource != null && Boolean.TRUE.equals(resource.getUserModified());
    }

    // ==================== Modification Detection ====================

    @Override
    public List<ResourceDiff> detectModifications(Long tenantId, ResourceType type, String code) {
        PluginResource resource = findByTypeAndCode(tenantId, type, code);
        if (resource == null || resource.getImportSnapshot() == null) {
            return Collections.emptyList();
        }

        Map<String, Object> snapshot = resource.getImportSnapshot();
        Map<String, Object> current = getCurrentDatabaseState(tenantId, type, code);

        return compareStates(snapshot, current);
    }

    @Override
    public Map<String, Object> getCurrentDatabaseState(Long tenantId, ResourceType type, String code) {
        // NOTE: JdbcTemplate is used here because this method queries arbitrary tables
        // with dynamic table/column names. This cannot be expressed as a single Mapper method.
        String tableName = type.getTableName();
        String codeColumn = getCodeColumn(type);
        String activeCondition = getActiveCondition(type);

        try {
            return jdbcTemplate.queryForObject(
                    String.format("SELECT * FROM %s WHERE tenant_id = ? AND %s = ? %s",
                            tableName, codeColumn, activeCondition),
                    (rs, rowNum) -> {
                        Map<String, Object> state = new HashMap<>();
                        var metadata = rs.getMetaData();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            String colName = metadata.getColumnName(i);
                            Object value = rs.getObject(i);
                            if (value instanceof org.postgresql.util.PGobject pgObj) {
                                try {
                                    value = objectMapper.readValue(pgObj.getValue(), new TypeReference<Map<String, Object>>() {});
                                } catch (Exception e) {
                                    value = pgObj.getValue();
                                }
                            }
                            state.put(colName, value);
                        }
                        return state;
                    }, tenantId, code);
        } catch (Exception e) {
            log.warn("Failed to get current state for {} {}: {}", type, code, e.getMessage());
            return new HashMap<>();
        }
    }

    @Override
    public List<ResourceDiff> compareStates(Map<String, Object> original, Map<String, Object> current) {
        if (original == null || current == null) {
            return Collections.emptyList();
        }

        List<ResourceDiff> diffs = new ArrayList<>();
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(original.keySet());
        allKeys.addAll(current.keySet());

        for (String key : allKeys) {
            if (IGNORED_FIELDS.contains(key) || IGNORED_FIELDS.contains(toSnakeCase(key))) {
                continue;
            }

            Object origValue = original.get(key);
            Object currValue = current.get(key);

            if (!Objects.equals(origValue, currValue)) {
                diffs.add(ResourceDiff.builder()
                        .field(key)
                        .original(origValue)
                        .current(currValue)
                        .description(formatDiffDescription(key, origValue, currValue))
                        .build());
            }
        }

        return diffs;
    }

    // ==================== Uninstall Operations ====================

    @Override
    public UninstallPreviewResult generateUninstallPreview(String pluginPid, Long tenantId) {
        PluginRecord plugin = pluginRecordMapper.findByPid(pluginPid);
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin not found: " + pluginPid);
        }

        List<PluginResource> resources = findByPluginPid(pluginPid);

        List<ResourceUninstallInfo> willDelete = new ArrayList<>();
        List<ResourceUninstallInfo> needsDecision = new ArrayList<>();
        List<ResourceUninstallInfo> willKeep = new ArrayList<>();

        for (PluginResource resource : resources) {
            ResourceUninstallInfo info = buildResourceUninstallInfo(resource, tenantId);

            OwnershipType ownership = resource.getOwnershipTypeEnum();
            boolean modified = Boolean.TRUE.equals(resource.getUserModified());

            if (ownership == OwnershipType.USER_CLAIMED) {
                info.setSuggestedDecision(null);
                willKeep.add(info);
            } else if (ownership == OwnershipType.PLUGIN_OWNED) {
                info.setSuggestedDecision(UninstallDecision.DELETE);
                willDelete.add(info);
            } else if (ownership == OwnershipType.SHARED) {
                if (modified) {
                    List<ResourceDiff> diffs = detectModifications(
                            tenantId, resource.getResourceTypeEnum(), resource.getResourceCode());
                    if (!diffs.isEmpty()) {
                        info.setDiffs(diffs);
                        info.setSuggestedDecision(UninstallDecision.KEEP_AND_DETACH);
                        needsDecision.add(info);
                    } else {
                        info.setSuggestedDecision(UninstallDecision.DELETE);
                        willDelete.add(info);
                    }
                } else {
                    info.setSuggestedDecision(UninstallDecision.DELETE);
                    willDelete.add(info);
                }
            }
        }

        Comparator<ResourceUninstallInfo> byType = Comparator.comparingInt(
                info -> info.getType().getImportOrder());
        willDelete.sort(byType);
        needsDecision.sort(byType);
        willKeep.sort(byType);

        Map<String, Integer> summaryCounts = new HashMap<>();
        for (PluginResource resource : resources) {
            String typeDisplay = resource.getResourceTypeEnum().getDisplayName();
            summaryCounts.merge(typeDisplay, 1, Integer::sum);
        }

        return UninstallPreviewResult.builder()
                .pluginPid(pluginPid)
                .pluginId(plugin.getPluginId())
                .pluginName(plugin.getDisplayName())
                .pluginVersion(plugin.getVersion())
                .willDelete(willDelete)
                .needsDecision(needsDecision)
                .willKeep(willKeep)
                .summaryCounts(summaryCounts)
                .hasConflicts(!needsDecision.isEmpty())
                .totalResources(resources.size())
                .build();
    }

    @Override
    @Transactional
    public UninstallResult executeUninstall(String pluginPid, Long tenantId, UninstallRequest request) {
        UninstallPreviewResult preview = generateUninstallPreview(pluginPid, tenantId);

        if (!request.isForce() && preview.isHasConflicts()) {
            for (ResourceUninstallInfo resource : preview.getNeedsDecision()) {
                if (request.getDecisions() == null ||
                        !request.getDecisions().containsKey(resource.getCode())) {
                    throw new IllegalArgumentException(
                            "Missing decision for modified resource: " + resource.getCode());
                }
            }
        }

        List<String> deletedResources = new ArrayList<>();
        List<String> detachedResources = new ArrayList<>();
        int keptCount = preview.getWillKeep().size();

        try {
            for (ResourceUninstallInfo info : preview.getWillDelete()) {
                PluginResource resource = findByTypeAndCode(tenantId, info.getType(), info.getCode());
                if (resource != null) {
                    deleteResource(resource);
                    deletedResources.add(info.getCode());
                }
            }

            for (ResourceUninstallInfo info : preview.getNeedsDecision()) {
                UninstallDecision decision = request.isForce()
                        ? UninstallDecision.DELETE
                        : request.getDecisions().get(info.getCode());

                PluginResource resource = findByTypeAndCode(tenantId, info.getType(), info.getCode());
                if (resource == null) continue;

                switch (decision) {
                    case DELETE -> {
                        deleteResource(resource);
                        deletedResources.add(info.getCode());
                    }
                    case KEEP_AND_DETACH -> {
                        detachResource(resource);
                        detachedResources.add(info.getCode());
                    }
                    case SKIP -> {
                        keptCount++;
                    }
                }
            }

            // Clean up plugin resource tracking records
            for (String code : deletedResources) {
                resourceMapper.deleteByPluginPidAndCode(pluginPid, code);
            }

            return UninstallResult.builder()
                    .success(true)
                    .pluginPid(pluginPid)
                    .pluginId(preview.getPluginId())
                    .deletedCount(deletedResources.size())
                    .detachedCount(detachedResources.size())
                    .keptCount(keptCount)
                    .deletedResources(deletedResources)
                    .detachedResources(detachedResources)
                    .uninstalledAt(Instant.now())
                    .build();

        } catch (Exception e) {
            log.error("Failed to uninstall plugin {}: {}", pluginPid, e.getMessage(), e);
            return UninstallResult.builder()
                    .success(false)
                    .pluginPid(pluginPid)
                    .pluginId(preview.getPluginId())
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    @Transactional
    public void deleteResource(PluginResource resource) {
        ResourceType type = resource.getResourceTypeEnum();
        String resourcePid = resource.getResourcePid();

        if (type == ResourceType.MODEL_FIELD_BINDING) {
            // Binding table now has pid column — use it for soft-delete
            if (resourcePid != null) {
                bindingMapper.softDeleteByPid(resourcePid);
            } else {
                log.warn("Cannot delete binding: no resourcePid for resource code {}", resource.getResourceCode());
            }
        } else {
            // All other resource tables support pid-based soft-delete
            String tableName = type.getTableName();
            // NOTE: JdbcTemplate used here for dynamic table name in soft-delete.
            // Each resource type maps to a different table, so a single Mapper method cannot cover all.
            jdbcTemplate.update(
                    String.format("UPDATE %s SET deleted_flag = TRUE, updated_at = NOW() WHERE pid = ?", tableName),
                    resourcePid);
        }

        log.info("Deleted resource: {} {} ({})", type, resource.getResourceCode(), resourcePid);
    }

    @Override
    @Transactional
    public void detachResource(PluginResource resource) {
        // Update ownership to USER_CLAIMED via mapper
        resource.setOwnershipType(OwnershipType.USER_CLAIMED.name());
        resource.setUpdatedAt(Instant.now());
        resourceMapper.updateById(resource);

        // Remove plugin_pid from the actual resource table
        ResourceType type = resource.getResourceTypeEnum();
        if (type != ResourceType.MODEL_FIELD_BINDING) {
            String tableName = type.getTableName();
            // NOTE: JdbcTemplate used here for dynamic table name (same reason as deleteResource)
            jdbcTemplate.update(
                    String.format("UPDATE %s SET plugin_pid = NULL, updated_at = NOW() WHERE pid = ?", tableName),
                    resource.getResourcePid());
        }

        log.info("Detached resource: {} {} from plugin {}",
                resource.getResourceType(), resource.getResourceCode(), resource.getPluginPid());
    }

    // ==================== Bulk Operations ====================

    @Override
    public List<PluginResource> findModifiedResources(String pluginPid) {
        return resourceMapper.findModifiedByPluginPid(pluginPid);
    }

    @Override
    public List<PluginResource> findUserClaimedResources(String pluginPid) {
        return resourceMapper.findUserClaimedByPluginPid(pluginPid);
    }

    @Override
    public Map<OwnershipType, Integer> countByOwnershipType(String pluginPid) {
        Map<OwnershipType, Integer> counts = new EnumMap<>(OwnershipType.class);
        for (OwnershipType type : OwnershipType.values()) {
            counts.put(type, 0);
        }

        List<PluginResource> resources = resourceMapper.findByPluginPid(pluginPid);
        for (PluginResource resource : resources) {
            OwnershipType type = resource.getOwnershipTypeEnum();
            counts.merge(type, 1, Integer::sum);
        }

        return counts;
    }

    // ==================== Helper Methods ====================

    private ResourceUninstallInfo buildResourceUninstallInfo(PluginResource resource, Long tenantId) {
        return ResourceUninstallInfo.builder()
                .pid(resource.getPid())
                .type(resource.getResourceTypeEnum())
                .code(resource.getResourceCode())
                .name(resource.getResourceName())
                .ownershipType(resource.getOwnershipTypeEnum())
                .modified(Boolean.TRUE.equals(resource.getUserModified()))
                .claimed(resource.getOwnershipTypeEnum() == OwnershipType.USER_CLAIMED)
                .build();
    }

    private String getCodeColumn(ResourceType type) {
        return switch (type) {
            case MENU -> "name";
            case PAGE -> "page_key";
            case PROCESS -> "process_key";
            default -> "code";
        };
    }

    private String getActiveCondition(ResourceType type) {
        return switch (type) {
            case NAMED_QUERY -> "AND status <> 'archived'";
            default -> "AND deleted_flag = FALSE";
        };
    }

    private String formatDiffDescription(String field, Object original, Object current) {
        String origStr = original != null ? truncate(original.toString(), 50) : "(empty)";
        String currStr = current != null ? truncate(current.toString(), 50) : "(empty)";
        return String.format("'%s' changed from '%s' to '%s'", field, origStr, currStr);
    }

    private String truncate(String str, int maxLen) {
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 3) + "...";
    }

    private String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
