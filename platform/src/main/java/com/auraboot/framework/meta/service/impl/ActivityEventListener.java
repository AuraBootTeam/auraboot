package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.ActivityService;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final CommandService commandService;
    private final DynamicDataMapper dynamicDataMapper;

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
        String operationType = resolveOperationType(event);
        String recordLabel = resolveRecordLabel(event);

        if (StringUtils.hasText(recordLabel)) {
            return switch (operationType) {
                case "create" -> "Created " + recordLabel;
                case "update" -> "Updated " + recordLabel;
                case "delete" -> "Deleted " + recordLabel;
                case "state_transition" -> "State changed: " + recordLabel;
                default -> recordLabel;
            };
        }

        String commandDisplayName = resolveCommandDisplayName(event.getCommandCode());
        if (StringUtils.hasText(commandDisplayName)) {
            return commandDisplayName;
        }

        String commandCode = event.getCommandCode();
        return switch (operationType) {
            case "create" -> StringUtils.hasText(commandCode) ? "Created record via " + commandCode : "Created record";
            case "update" -> StringUtils.hasText(commandCode) ? "Updated record via " + commandCode : "Updated record";
            case "delete" -> StringUtils.hasText(commandCode) ? "Deleted record via " + commandCode : "Deleted record";
            case "state_transition" ->
                    StringUtils.hasText(commandCode) ? "State transition via " + commandCode : "State transition";
            default -> StringUtils.hasText(commandCode) ? "Executed " + commandCode : "Record activity";
        };
    }

    private String resolveOperationType(CommandCompletedEvent event) {
        if (StringUtils.hasText(event.getOperationType())) {
            return event.getOperationType();
        }
        try {
            if (!StringUtils.hasText(event.getCommandCode())) {
                return "system";
            }
            CommandDefinitionDTO command = commandService.findByCode(event.getCommandCode());
            return StringUtils.hasText(command.getType()) ? command.getType() : "system";
        } catch (Exception e) {
            log.debug("Could not resolve operation type from command {}: {}",
                    event.getCommandCode(), e.getMessage());
            return "system";
        }
    }

    private String resolveCommandDisplayName(String commandCode) {
        if (!StringUtils.hasText(commandCode)) {
            return null;
        }
        try {
            CommandDefinitionDTO command = commandService.findByCode(commandCode);
            return StringUtils.hasText(command.getDisplayName()) ? command.getDisplayName() : null;
        } catch (Exception e) {
            log.debug("Could not resolve display name for command {}: {}", commandCode, e.getMessage());
            return null;
        }
    }

    private String resolveRecordLabel(CommandCompletedEvent event) {
        List<FieldDefinition> displayFields = resolvePreferredLabelFields(event.getModelCode());
        if (displayFields.isEmpty()) {
            return null;
        }

        String fromPayload = resolveRecordLabelFromMap(displayFields, event.getPayload());
        if (StringUtils.hasText(fromPayload)) {
            return fromPayload;
        }

        return resolveRecordLabelFromDatabase(event.getTenantId(), event.getModelCode(), event.getRecordId(), displayFields);
    }

    private List<FieldDefinition> resolvePreferredLabelFields(String modelCode) {
        List<FieldDefinition> preferredFields = metaModelService.getDisplayFields(modelCode).stream()
                .filter(field -> !"pid".equals(field.getCode()))
                .collect(Collectors.toList());
        if (!preferredFields.isEmpty()) {
            return preferredFields;
        }

        Optional<ModelDefinition> modelDefinition = metaModelService.getModelDefinition(modelCode);
        if (modelDefinition.isPresent()) {
            Map<String, Object> extension = modelDefinition.get().getExtension();
            String titleFieldCode = extractExtensionText(extension, "titleField");
            if (StringUtils.hasText(titleFieldCode) && metaModelService.isFieldExists(modelCode, titleFieldCode)) {
                return List.of(metaModelService.getFieldDefinition(modelCode, titleFieldCode));
            }
        }

        return metaModelService.getModelFields(modelCode).stream()
                .filter(field -> !"pid".equals(field.getCode()))
                .filter(field -> isCommonLabelField(field.getCode()))
                .collect(Collectors.toList());
    }

    private String extractExtensionText(Map<String, Object> extension, String key) {
        if (extension == null) {
            return null;
        }
        Object value = extension.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private boolean isCommonLabelField(String fieldCode) {
        if (!StringUtils.hasText(fieldCode)) {
            return false;
        }
        String normalized = fieldCode.toLowerCase();
        return normalized.equals("name")
                || normalized.equals("title")
                || normalized.equals("subject")
                || normalized.endsWith("_name")
                || normalized.endsWith("_title")
                || normalized.endsWith("_subject")
                || normalized.endsWith("_code");
    }

    private String resolveRecordLabelFromMap(List<FieldDefinition> displayFields, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        for (FieldDefinition field : displayFields) {
            Object value = source.get(field.getCode());
            if (hasMeaningfulValue(value)) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private String resolveRecordLabelFromDatabase(Long tenantId, String modelCode, String recordId,
                                                  List<FieldDefinition> displayFields) {
        if (tenantId == null || !StringUtils.hasText(recordId)) {
            return null;
        }
        try {
            String tableName = metaModelService.getTableName(modelCode);
            String selectColumns = displayFields.stream()
                    .map(field -> field.getColumnName() + " AS " + field.getCode())
                    .collect(Collectors.joining(", "));
            String sql = """
                    SELECT %s
                      FROM %s
                     WHERE tenant_id = #{params.tenantId}
                       AND pid = #{params.recordId}
                     LIMIT 1
                    """.formatted(selectColumns, tableName);
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of(
                    "tenantId", tenantId,
                    "recordId", recordId
            ));
            if (rows.isEmpty()) {
                return null;
            }
            return resolveRecordLabelFromMap(displayFields, rows.getFirst());
        } catch (Exception e) {
            log.debug("Could not resolve record label for model={}, record={}: {}",
                    modelCode, recordId, e.getMessage());
            return null;
        }
    }

    private boolean hasMeaningfulValue(Object value) {
        if (value == null) {
            return false;
        }
        String text = value.toString().trim();
        return !text.isEmpty() && !"null".equalsIgnoreCase(text);
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
