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
 * Executor for CREATE_RECORD action type
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateRecordExecutor implements ActionExecutor {

    private final DynamicDataService dynamicDataService;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("CREATE_RECORD action requires config");
        }

        String modelCode = (String) config.get("modelCode");
        if (modelCode == null || modelCode.isBlank()) {
            throw new IllegalArgumentException("CREATE_RECORD action requires modelCode");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) config.get("fields");
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("CREATE_RECORD action requires fields");
        }

        Map<String, Object> processedFields = processFieldValues(fields, context);

        log.info("Creating record: modelCode={}, fields={}", modelCode, processedFields.keySet());

        Map<String, Object> created = dynamicDataService.create(modelCode, processedFields);

        return Map.of(
                "success", true,
                "modelCode", modelCode,
                "record", created
        );
    }

    @Override
    public boolean supports(String actionType) {
        return "create_record".equals(actionType);
    }

    private Map<String, Object> processFieldValues(Map<String, Object> fields, Map<String, Object> context) {
        Map<String, Object> processed = new HashMap<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            Object value = entry.getValue();
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
