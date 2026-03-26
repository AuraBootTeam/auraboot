package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Listens for CommandCompletedEvent and records field-level changes
 * for fields that have been configured for auditing.
 * <p>
 * Runs asynchronously after the command transaction commits,
 * so it never blocks the main command response.
 * </p>
 * <p>
 * The before-snapshot is carried in the event metadata (populated by
 * CommandExecutorImpl). The after-snapshot is read from the database
 * (safe because we run after commit).
 * </p>
 *
 * @since 6.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FieldChangeEventListener {

    private final FieldChangeAuditService fieldChangeAuditService;
    private final MetaModelService metaModelService;
    private final DynamicDataMapper dynamicDataMapper;

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(CommandCompletedEvent event) {
        try {
            String modelCode = event.getModelCode();
            if (!StringUtils.hasText(modelCode)) {
                return;
            }

            String recordIdStr = event.getRecordId();
            if (!StringUtils.hasText(recordIdStr)) {
                return;
            }

            Long recordId = parseLong(recordIdStr);
            if (recordId == null) {
                return;
            }

            Long tenantId = event.getTenantId();
            Map<String, Object> metadata = event.getMetadata();

            // Extract actor info
            Long actorId = extractLong(metadata, "actorId");
            String actorName = extractString(metadata, "actorName");

            // Extract before-snapshot from event metadata (populated by CommandExecutorImpl)
            @SuppressWarnings("unchecked")
            Map<String, Object> beforeData = metadata != null
                    ? (Map<String, Object>) metadata.get("beforeSnapshot") : null;

            // Read after-snapshot from database (safe — we're after commit)
            String operationType = event.getOperationType();
            Map<String, Object> afterData = null;
            if (!"delete".equalsIgnoreCase(operationType)) {
                afterData = readRecordFromDb(tenantId, modelCode, recordId);
            }

            // Delegate to the audit service for diffing and recording
            fieldChangeAuditService.recordFieldChanges(
                    tenantId, modelCode, recordId, event.getCommandCode(),
                    beforeData, afterData, actorId != null ? actorId : 0L, actorName);

        } catch (Exception e) {
            // Field change audit failures must never break the main flow.
            log.error("Failed to record field changes for command={}, model={}, record={}: {}",
                    event.getCommandCode(), event.getModelCode(),
                    event.getRecordId(), e.getMessage(), e);
        }
    }

    /**
     * Read the current state of a record from its dynamic table.
     */
    private Map<String, Object> readRecordFromDb(Long tenantId, String modelCode, Long recordId) {
        try {
            String tableName = metaModelService.getTableName(modelCode);
            if (!StringUtils.hasText(tableName)) {
                return null;
            }
            SqlSafetyUtils.validateIdentifier(tableName, "fieldChange tableName");
            String sql = "SELECT * FROM " + tableName
                    + " WHERE tenant_id = #{params.tenantId} AND id = #{params.recordId}";
            Map<String, Object> params = Map.of("tenantId", tenantId, "recordId", recordId);
            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql, params);
            if (result != null && !result.isEmpty()) {
                return result.get(0);
            }
        } catch (Exception e) {
            log.debug("Failed to read record snapshot for field change audit: model={}, id={}: {}",
                    modelCode, recordId, e.getMessage());
        }
        return null;
    }

    private Long extractLong(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) {
            try { return Long.parseLong((String) val); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private String extractString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { return null; }
    }
}
