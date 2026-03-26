package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.FieldAuditConfig;
import com.auraboot.framework.meta.entity.FieldChangeLog;
import com.auraboot.framework.meta.mapper.FieldAuditConfigMapper;
import com.auraboot.framework.meta.mapper.FieldChangeLogMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for field-level change auditing.
 * <p>
 * Tracks changes to specifically configured fields, recording old/new values
 * with actor context. Uses a per-tenant config cache to avoid repeated DB lookups.
 * </p>
 *
 * @since 6.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldChangeAuditService {

    private final FieldChangeLogMapper changeLogMapper;
    private final FieldAuditConfigMapper auditConfigMapper;
    private final MetaModelService metaModelService;
    private final ObjectMapper objectMapper;

    /**
     * Cache key: "tenantId:modelCode" -> Map of fieldCode -> FieldAuditConfig.
     * Cleared when config is updated.
     */
    private final ConcurrentHashMap<String, Map<String, FieldAuditConfig>> configCache = new ConcurrentHashMap<>();

    // =====================================================================
    // Core: Record field changes
    // =====================================================================

    /**
     * Diff beforeData vs afterData and record changes for all configured fields.
     *
     * @param tenantId    tenant context
     * @param modelCode   which model changed
     * @param recordId    which record changed
     * @param commandCode which command triggered the change
     * @param beforeData  record state before the change (null for CREATE)
     * @param afterData   record state after the change (null for DELETE)
     * @param actorId     who made the change
     * @param actorName   actor display name
     */
    @Transactional
    public void recordFieldChanges(Long tenantId, String modelCode, Long recordId,
                                    String commandCode, Map<String, Object> beforeData,
                                    Map<String, Object> afterData, Long actorId,
                                    String actorName) {
        if (!StringUtils.hasText(modelCode)) {
            return;
        }

        // Load the audit config for this model (from cache)
        Map<String, FieldAuditConfig> configMap = getConfigMap(tenantId, modelCode);
        if (configMap.isEmpty()) {
            // No fields configured for auditing on this model — skip entirely
            return;
        }

        // Resolve field labels from model definition
        Map<String, String> fieldLabels = resolveFieldLabels(modelCode);
        Map<String, String> fieldTypes = resolveFieldTypes(modelCode);

        // Diff and create change log entries
        List<FieldChangeLog> changeLogs = new ArrayList<>();
        Instant now = Instant.now();

        if (beforeData == null && afterData != null) {
            // CREATE: track all configured fields that have values
            for (Map.Entry<String, FieldAuditConfig> entry : configMap.entrySet()) {
                String fieldCode = entry.getKey();
                Object newVal = afterData.get(fieldCode);
                if (newVal != null) {
                    changeLogs.add(buildChangeLog(tenantId, modelCode, recordId, commandCode,
                            fieldCode, fieldLabels.get(fieldCode), null, newVal,
                            fieldTypes.getOrDefault(fieldCode, "string"), "added",
                            actorId, actorName, now));
                }
            }
        } else if (beforeData != null && afterData == null) {
            // DELETE: track all configured fields that had values
            for (Map.Entry<String, FieldAuditConfig> entry : configMap.entrySet()) {
                String fieldCode = entry.getKey();
                Object oldVal = beforeData.get(fieldCode);
                if (oldVal != null) {
                    changeLogs.add(buildChangeLog(tenantId, modelCode, recordId, commandCode,
                            fieldCode, fieldLabels.get(fieldCode), oldVal, null,
                            fieldTypes.getOrDefault(fieldCode, "string"), "removed",
                            actorId, actorName, now));
                }
            }
        } else if (beforeData != null && afterData != null) {
            // UPDATE: only track configured fields that actually changed
            for (Map.Entry<String, FieldAuditConfig> entry : configMap.entrySet()) {
                String fieldCode = entry.getKey();
                Object oldVal = beforeData.get(fieldCode);
                Object newVal = afterData.get(fieldCode);
                if (!Objects.equals(asString(oldVal), asString(newVal))) {
                    changeLogs.add(buildChangeLog(tenantId, modelCode, recordId, commandCode,
                            fieldCode, fieldLabels.get(fieldCode), oldVal, newVal,
                            fieldTypes.getOrDefault(fieldCode, "string"), "modified",
                            actorId, actorName, now));
                }
            }
        }

        if (changeLogs.isEmpty()) {
            return;
        }

        // Batch insert all change logs
        for (FieldChangeLog cl : changeLogs) {
            changeLogMapper.insert(cl);
        }

        log.debug("Recorded {} field changes for model={}, record={}, command={}",
                changeLogs.size(), modelCode, recordId, commandCode);
    }

    // =====================================================================
    // Query: Field history
    // =====================================================================

    /**
     * Get all field changes for a specific record.
     */
    public List<FieldChangeLog> getRecordHistory(Long tenantId, String modelCode, Long recordId) {
        return changeLogMapper.getByModelAndRecord(tenantId, modelCode, recordId);
    }

    /**
     * Get change history for a specific field on a record.
     */
    public List<FieldChangeLog> getFieldHistory(Long tenantId, String modelCode,
                                                 Long recordId, String fieldCode) {
        return changeLogMapper.getByField(tenantId, modelCode, recordId, fieldCode);
    }

    /**
     * Get all changes made by a specific actor within a time range.
     */
    public List<FieldChangeLog> getChangesByActor(Long tenantId, Long actorId,
                                                    Instant startTime, Instant endTime) {
        return changeLogMapper.getByActor(tenantId, actorId, startTime, endTime);
    }

    /**
     * Get a change report for a model within a time range.
     */
    public Map<String, Object> getChangeReport(Long tenantId, String modelCode,
                                                Instant startTime, Instant endTime) {
        List<FieldChangeLog> changes = changeLogMapper.getByModelAndTimeRange(
                tenantId, modelCode, startTime, endTime);
        long totalCount = changeLogMapper.countByModelAndTimeRange(
                tenantId, modelCode, startTime, endTime);

        // Group by field for summary
        Map<String, Long> fieldChangeCounts = changes.stream()
                .collect(Collectors.groupingBy(FieldChangeLog::getFieldCode, Collectors.counting()));

        // Group by actor for summary
        Map<String, Long> actorChangeCounts = changes.stream()
                .filter(c -> c.getActorName() != null)
                .collect(Collectors.groupingBy(FieldChangeLog::getActorName, Collectors.counting()));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("modelCode", modelCode);
        report.put("startTime", startTime.toString());
        report.put("endTime", endTime.toString());
        report.put("totalChanges", totalCount);
        report.put("fieldChangeCounts", fieldChangeCounts);
        report.put("actorChangeCounts", actorChangeCounts);
        report.put("recentChanges", changes.size() > 100 ? changes.subList(0, 100) : changes);
        return report;
    }

    // =====================================================================
    // Config management
    // =====================================================================

    /**
     * Configure field auditing for a specific field on a model.
     */
    @Transactional
    public FieldAuditConfig configureFieldAudit(Long tenantId, String modelCode, String fieldCode,
                                                  boolean enabled, boolean requireReason,
                                                  boolean notifyOnChange) {
        FieldAuditConfig existing = auditConfigMapper.getByModelAndField(tenantId, modelCode, fieldCode);
        if (existing != null) {
            existing.setEnabled(enabled);
            existing.setRequireReason(requireReason);
            existing.setNotifyOnChange(notifyOnChange);
            auditConfigMapper.updateById(existing);
            evictConfigCache(tenantId, modelCode);
            return existing;
        } else {
            FieldAuditConfig config = new FieldAuditConfig();
            config.setTenantId(tenantId);
            config.setModelCode(modelCode);
            config.setFieldCode(fieldCode);
            config.setEnabled(enabled);
            config.setRequireReason(requireReason);
            config.setNotifyOnChange(notifyOnChange);
            config.setCreatedAt(Instant.now());
            auditConfigMapper.insert(config);
            evictConfigCache(tenantId, modelCode);
            return config;
        }
    }

    /**
     * Bulk configure field auditing for multiple fields on a model.
     */
    @Transactional
    public List<FieldAuditConfig> bulkConfigureFieldAudit(Long tenantId, String modelCode,
                                                            List<FieldAuditConfigRequest> configs) {
        List<FieldAuditConfig> results = new ArrayList<>();
        for (FieldAuditConfigRequest req : configs) {
            results.add(configureFieldAudit(tenantId, modelCode, req.fieldCode(),
                    req.enabled(), req.requireReason(), req.notifyOnChange()));
        }
        return results;
    }

    /**
     * Get audit config for a model (all entries including disabled).
     */
    public List<FieldAuditConfig> getAuditConfig(Long tenantId, String modelCode) {
        return auditConfigMapper.getAllByModel(tenantId, modelCode);
    }

    // =====================================================================
    // Internal helpers
    // =====================================================================

    /**
     * Load the audit config map from cache (or DB on cache miss).
     * Key = fieldCode, Value = FieldAuditConfig (enabled only).
     */
    private Map<String, FieldAuditConfig> getConfigMap(Long tenantId, String modelCode) {
        String cacheKey = tenantId + ":" + modelCode;
        return configCache.computeIfAbsent(cacheKey, k -> {
            List<FieldAuditConfig> configs = auditConfigMapper.getEnabledByModel(tenantId, modelCode);
            Map<String, FieldAuditConfig> map = new HashMap<>();
            for (FieldAuditConfig c : configs) {
                map.put(c.getFieldCode(), c);
            }
            return map;
        });
    }

    /**
     * Evict the config cache for a tenant+model when config changes.
     */
    private void evictConfigCache(Long tenantId, String modelCode) {
        configCache.remove(tenantId + ":" + modelCode);
    }

    /**
     * Resolve field labels from the model definition.
     */
    private Map<String, String> resolveFieldLabels(String modelCode) {
        Map<String, String> labels = new HashMap<>();
        try {
            Optional<ModelDefinition> modelOpt = metaModelService.getModelDefinition(modelCode);
            if (modelOpt.isPresent() && modelOpt.get().getFields() != null) {
                for (FieldDefinition fd : modelOpt.get().getFields()) {
                    String label = fd.getDisplayName() != null ? fd.getDisplayName() : fd.getName();
                    if (label == null) label = fd.getCode();
                    labels.put(fd.getCode(), label);
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve field labels for model {}: {}", modelCode, e.getMessage());
        }
        return labels;
    }

    /**
     * Resolve field data types from the model definition.
     */
    private Map<String, String> resolveFieldTypes(String modelCode) {
        Map<String, String> types = new HashMap<>();
        try {
            Optional<ModelDefinition> modelOpt = metaModelService.getModelDefinition(modelCode);
            if (modelOpt.isPresent() && modelOpt.get().getFields() != null) {
                for (FieldDefinition fd : modelOpt.get().getFields()) {
                    types.put(fd.getCode(), mapDataType(fd.getDataType()));
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve field types for model {}: {}", modelCode, e.getMessage());
        }
        return types;
    }

    /**
     * Map internal data types to the field change log value_type enum.
     */
    private String mapDataType(String dataType) {
        if (dataType == null) return "string";
        return switch (dataType.toUpperCase()) {
            case "integer", "decimal", "float", "double", "bigint", "number" -> "number";
            case "boolean" -> "boolean";
            case "date", "datetime", "timestamp" -> "date";
            case "reference", "foreign_key" -> "reference";
            case "enum", "dict" -> "enum";
            default -> "string";
        };
    }

    private FieldChangeLog buildChangeLog(Long tenantId, String modelCode, Long recordId,
                                           String commandCode, String fieldCode, String fieldLabel,
                                           Object oldValue, Object newValue, String valueType,
                                           String changeType, Long actorId, String actorName,
                                           Instant changedAt) {
        FieldChangeLog cl = new FieldChangeLog();
        cl.setTenantId(tenantId);
        cl.setModelCode(modelCode);
        cl.setRecordId(recordId);
        cl.setCommandCode(commandCode);
        cl.setFieldCode(fieldCode);
        cl.setFieldLabel(fieldLabel != null ? fieldLabel : fieldCode);
        cl.setOldValue(asString(oldValue));
        cl.setNewValue(asString(newValue));
        cl.setValueType(valueType);
        cl.setChangeType(changeType);
        cl.setActorId(actorId);
        cl.setActorName(actorName);
        cl.setChangedAt(changedAt);
        return cl;
    }

    /**
     * Convert any value to its string representation for storage.
     */
    private String asString(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof Map || value instanceof List) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                return value.toString();
            }
        }
        return value.toString();
    }

    /**
     * DTO for bulk configuration requests.
     */
    public record FieldAuditConfigRequest(
            String fieldCode,
            boolean enabled,
            boolean requireReason,
            boolean notifyOnChange
    ) {}
}
