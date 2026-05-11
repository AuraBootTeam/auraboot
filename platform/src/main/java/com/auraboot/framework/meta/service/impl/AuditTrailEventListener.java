package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Listens for CommandCompletedEvent and records a tamper-proof audit trail entry.
 *
 * Uses @TransactionalEventListener(AFTER_COMMIT) + @Async to ensure:
 * 1. The audit record is only created if the command transaction commits successfully
 * 2. The audit recording runs in a separate thread, not blocking the command response
 *
 * The actor context (userId, username) is captured from the event's metadata,
 * which is populated before the event is published inside the command executor.
 *
 * @since 6.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditTrailEventListener {

    private final AuditTrailService auditTrailService;
    private final ObjectMapper objectMapper;

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(CommandCompletedEvent event) {
        try {
            AuditTrailEvent auditEvent = buildAuditEvent(event);
            auditTrailService.recordAudit(auditEvent);
        } catch (Exception e) {
            // Audit trail failures must never break the main flow.
            // Log at error level for monitoring/alerting.
            log.error("Failed to record audit trail for command={}, model={}, record={}: {}",
                    event.getCommandCode(), event.getModelCode(),
                    event.getRecordId(), e.getMessage(), e);
        }
    }

    private AuditTrailEvent buildAuditEvent(CommandCompletedEvent event) {
        // Extract actor information from event metadata (populated by CommandExecutor)
        Map<String, Object> eventMetadata = event.getMetadata();
        Long actorId = extractLong(eventMetadata, "actorId");
        String actorName = extractString(eventMetadata, "actorName");
        String actorIp = extractString(eventMetadata, "actorIp");

        // Parse entity ID from recordId string
        Long entityId = parseLong(event.getRecordId());
        String entityPid = entityId == null ? normalizePid(event.getRecordId()) : null;

        // Convert payload map to JsonNode for snapshot storage
        JsonNode afterSnapshot = objectMapper.valueToTree(event.getPayload());

        // Build metadata node from event metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (eventMetadata != null && !eventMetadata.isEmpty()) {
            metadata.putAll(eventMetadata);
        }
        if (StringUtils.hasText(entityPid)) {
            metadata.put("entityPid", entityPid);
        }
        JsonNode metadataNode = null;
        if (!metadata.isEmpty()) {
            metadataNode = objectMapper.valueToTree(metadata);
        }

        return AuditTrailEvent.builder()
                .tenantId(event.getTenantId())
                .eventType("command_executed")
                .entityType(event.getModelCode())
                .entityId(entityId)
                .entityPid(entityPid)
                .commandCode(event.getCommandCode())
                .operationType(event.getOperationType())
                .actorId(actorId != null ? actorId : 0L)
                .actorName(actorName)
                .actorIp(actorIp)
                .afterSnapshot(afterSnapshot)
                .metadata(metadataNode)
                .build();
    }

    private Long extractLong(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        if (val instanceof String) {
            try {
                return Long.parseLong((String) val);
            } catch (NumberFormatException e) {
                return null;
            }
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
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizePid(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
