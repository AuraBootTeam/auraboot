package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.ActivityService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Listens for CommandCompletedEvent and auto-records activity timeline entries
 * for DOCUMENT and MASTER model categories.
 *
 * Runs async after commit (same pattern as AuditTrailEventListener).
 * Activity timeline is user-facing (unlike audit trail which is for compliance).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityEventListener {

    private final ActivityService activityService;
    private final MetaModelService metaModelService;

    private static final Set<String> TRACKABLE_CATEGORIES = Set.of("document", "master");

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(CommandCompletedEvent event) {
        try {
            String modelCode = event.getModelCode();
            if (modelCode == null || modelCode.isBlank()) return;

            String recordId = event.getRecordId();
            if (recordId == null || recordId.isBlank()) return;

            // Check if model category is trackable
            if (!isTrackableModel(modelCode)) return;

            String operationType = event.getOperationType();
            String activityType = mapOperationToActivityType(operationType);
            String subject = buildSubject(event);

            // Extract actor info from metadata
            Map<String, Object> eventMeta = event.getMetadata();
            Long actorId = extractLong(eventMeta, "actorId");
            String actorName = extractString(eventMeta, "actorName");

            // Build activity metadata
            Map<String, Object> metadata = new HashMap<>();
            if (eventMeta != null && eventMeta.containsKey("beforeSnapshot")) {
                metadata.put("beforeSnapshot", eventMeta.get("beforeSnapshot"));
            }
            if (event.getPayload() != null) {
                // Only include status-related fields for state transitions
                if ("state_transition".equals(operationType)) {
                    metadata.put("payload", event.getPayload());
                }
            }

            activityService.recordSystemActivity(
                    event.getTenantId(),
                    modelCode,
                    recordId,
                    activityType,
                    subject,
                    event.getCommandCode(),
                    operationType,
                    actorId,
                    actorName,
                    metadata.isEmpty() ? null : metadata
            );

        } catch (Exception e) {
            // Activity recording must never break the main flow
            log.error("Failed to record activity for command={}, model={}, record={}: {}",
                    event.getCommandCode(), event.getModelCode(),
                    event.getRecordId(), e.getMessage(), e);
        }
    }

    private boolean isTrackableModel(String modelCode) {
        try {
            Optional<ModelDefinition> modelDef = metaModelService.getModelDefinition(modelCode);
            if (modelDef.isEmpty()) return false;
            String category = modelDef.get().getModelCategory();
            return category != null && TRACKABLE_CATEGORIES.contains(category);
        } catch (Exception e) {
            log.debug("Could not check model category for {}: {}", modelCode, e.getMessage());
            return false;
        }
    }

    private String mapOperationToActivityType(String operationType) {
        if (operationType == null) return "system";
        return switch (operationType) {
            case "create" -> "create";
            case "update" -> "update";
            case "delete" -> "delete";
            case "state_transition" -> "state_change";
            default -> "system";
        };
    }

    private String buildSubject(CommandCompletedEvent event) {
        String op = event.getOperationType();
        String cmd = event.getCommandCode();
        if ("state_transition".equals(op)) {
            return "State transition: " + cmd;
        }
        return op + " via " + cmd;
    }

    private Long extractLong(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private String extractString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
