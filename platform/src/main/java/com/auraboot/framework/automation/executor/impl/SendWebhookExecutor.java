package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for SEND_WEBHOOK action type.
 * Dispatches events to webhook subscriptions via WebhookDispatcher.
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SendWebhookExecutor implements ActionExecutor {

    private final WebhookDispatcher webhookDispatcher;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("SEND_WEBHOOK action requires config");
        }

        String eventType = (String) config.get("eventType");
        if (eventType == null || eventType.isBlank()) {
            eventType = "automation.action";
        }

        // Build payload from config + context
        Map<String, Object> payload = new HashMap<>();

        @SuppressWarnings("unchecked")
        Map<String, Object> customPayload = (Map<String, Object>) config.get("payload");
        if (customPayload != null) {
            payload.putAll(processPayload(customPayload, context));
        } else {
            // Default: send the trigger context
            payload.put("automationPid", context.get("automationPid"));
            payload.put("recordId", context.get("recordId"));
            payload.put("event", context.get("event"));
            if (context.containsKey("record")) {
                payload.put("record", context.get("record"));
            }
        }

        Long tenantId = context.containsKey("tenantId")
                ? ((Number) context.get("tenantId")).longValue() : null;

        log.info("Dispatching webhook: eventType={}, payloadKeys={}", eventType, payload.keySet());

        webhookDispatcher.dispatch(eventType, payload, tenantId);

        return Map.of(
                "success", true,
                "eventType", eventType,
                "dispatched", true
        );
    }

    @Override
    public boolean supports(String actionType) {
        return "send_webhook".equals(actionType);
    }

    private Map<String, Object> processPayload(Map<String, Object> payload, Map<String, Object> context) {
        Map<String, Object> processed = new HashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
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
