package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for UPDATE_RECORD action type
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateRecordExecutor implements ActionExecutor {

    private final DynamicDataService dynamicDataService;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("UPDATE_RECORD action requires config");
        }

        String modelCode = (String) config.get("modelCode");
        String recordId = (String) config.get("recordId");

        // Resolve variable substitution in recordId (e.g., ${trigger.recordId})
        if (recordId != null && recordId.startsWith("${") && recordId.endsWith("}")) {
            String varName = recordId.substring(2, recordId.length() - 1);
            Object resolved = resolveVariable(varName, context);
            recordId = resolved != null ? resolved.toString() : null;
        }

        // If recordId not specified, use the triggering record
        if (recordId == null || recordId.isBlank()) {
            recordId = (String) context.get("recordId");
        }

        // Get field updates
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldUpdates = (Map<String, Object>) config.get("fields");
        if (fieldUpdates == null || fieldUpdates.isEmpty()) {
            throw new IllegalArgumentException("UPDATE_RECORD action requires fields to update");
        }

        // Process field values (may contain expressions)
        Map<String, Object> processedUpdates = processFieldValues(fieldUpdates, context);

        log.info("Updating record: modelCode={}, recordId={}, fields={}",
                modelCode, recordId, processedUpdates.keySet());

        Map<String, Object> result = dynamicDataService.update(modelCode, recordId, processedUpdates);

        return Map.of(
                "success", true,
                "recordId", recordId,
                "updatedFields", processedUpdates.keySet()
        );
    }

    @Override
    public boolean supports(String actionType) {
        return "update_record".equals(actionType);
    }

    private Map<String, Object> processFieldValues(Map<String, Object> fields, Map<String, Object> context) {
        Map<String, Object> processed = new HashMap<>();

        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            Object value = entry.getValue();

            // Simple variable substitution for ${xxx} patterns
            if (value instanceof String strValue) {
                if (strValue.startsWith("${") && strValue.endsWith("}")) {
                    String varName = strValue.substring(2, strValue.length() - 1);
                    value = resolveVariable(varName, context);
                }
            }

            processed.put(entry.getKey(), value);
        }

        return processed;
    }

    private Object resolveVariable(String varName, Map<String, Object> context) {
        // Support dot notation like "record.fieldName"
        String[] parts = varName.split("\\.");
        Object current = context;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }

        return current;
    }
}
