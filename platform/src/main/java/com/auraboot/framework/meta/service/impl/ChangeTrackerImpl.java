package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ChangeRecord;
import com.auraboot.framework.meta.dto.FieldChange;
import com.auraboot.framework.meta.entity.DataChangeLog;
import com.auraboot.framework.meta.mapper.DataChangeLogMapper;
import com.auraboot.framework.meta.service.ChangeTracker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Implementation of ChangeTracker.
 * Computes field-level diffs and persists change logs.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeTrackerImpl implements ChangeTracker {

    private final DataChangeLogMapper changeLogMapper;
    private final ObjectMapper objectMapper;

    private static final Set<String> IGNORED_FIELDS = Set.of(
            "id", "tenant_id", "created_at", "updated_at", "created_by", "updated_by"
    );

    @Override
    public List<FieldChange> diff(Map<String, Object> before, Map<String, Object> after, String modelCode) {
        List<FieldChange> changes = new ArrayList<>();

        if (before == null && after == null) {
            return changes;
        }

        if (before == null) {
            // CREATE: all fields in after are new
            for (Map.Entry<String, Object> entry : after.entrySet()) {
                if (IGNORED_FIELDS.contains(entry.getKey())) continue;
                if (entry.getValue() != null) {
                    changes.add(FieldChange.builder()
                            .fieldCode(entry.getKey())
                            .fieldLabel(entry.getKey())
                            .oldValue(null)
                            .newValue(entry.getValue())
                            .build());
                }
            }
            return changes;
        }

        if (after == null) {
            // DELETE: all fields in before are removed
            for (Map.Entry<String, Object> entry : before.entrySet()) {
                if (IGNORED_FIELDS.contains(entry.getKey())) continue;
                if (entry.getValue() != null) {
                    changes.add(FieldChange.builder()
                            .fieldCode(entry.getKey())
                            .fieldLabel(entry.getKey())
                            .oldValue(entry.getValue())
                            .newValue(null)
                            .build());
                }
            }
            return changes;
        }

        // UPDATE: compare all fields
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(before.keySet());
        allKeys.addAll(after.keySet());

        for (String key : allKeys) {
            if (IGNORED_FIELDS.contains(key)) continue;

            Object oldVal = before.get(key);
            Object newVal = after.get(key);

            if (!Objects.equals(oldVal, newVal)) {
                changes.add(FieldChange.builder()
                        .fieldCode(key)
                        .fieldLabel(key)
                        .oldValue(oldVal)
                        .newValue(newVal)
                        .build());
            }
        }

        return changes;
    }

    @Override
    @Transactional
    public void recordChange(ChangeRecord record) {
        Long tenantId = MetaContext.getCurrentTenantId();

        DataChangeLog entity = new DataChangeLog();
        entity.setTenantId(tenantId);
        entity.setModelCode(record.getModelCode());
        entity.setRecordId(record.getRecordId());
        entity.setOperation(record.getOperation());
        entity.setChangedBy(record.getChangedBy());
        entity.setChangedAt(Instant.now());
        entity.setCommandCode(record.getCommandCode());
        entity.setClientRequestId(record.getClientRequestId());

        try {
            if (record.getChanges() != null && !record.getChanges().isEmpty()) {
                entity.setChanges(objectMapper.writeValueAsString(record.getChanges()));
            } else {
                entity.setChanges("[]");
            }
            if (record.getSnapshotBefore() != null) {
                entity.setSnapshotBefore(objectMapper.writeValueAsString(record.getSnapshotBefore()));
            }
            if (record.getSnapshotAfter() != null) {
                entity.setSnapshotAfter(objectMapper.writeValueAsString(record.getSnapshotAfter()));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize change record: {}", e.getMessage(), e);
            entity.setChanges("[]");
        }

        changeLogMapper.insert(entity);
        log.debug("Recorded change log: model={}, record={}, operation={}, fields={}",
                record.getModelCode(), record.getRecordId(), record.getOperation(),
                record.getChanges() != null ? record.getChanges().size() : 0);
    }
}
