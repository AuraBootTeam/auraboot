package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.bpm.service.BpmIntegrationService;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for the START_PROCESS action type — starts a BPM process instance.
 *
 * <p>Golden FINDING (2026-06-05): the {@code action-start-process} palette node shipped
 * without a backend executor, so {@code CompositeActionExecutor} threw
 * {@code UnsupportedOperationException("No executor found for action type: start_process")}
 * for every automation that used it. This executor closes that gap by delegating to
 * {@link BpmIntegrationService#startBusinessProcess} (the same entry point used by the
 * command pipeline's {@code start_process} post-action and the builtin start-approval
 * handler), so the visual designer's start-process node is actually usable.
 *
 * <p>Config (mirrors {@code nodes/actions.ts} action-start-process configSchema):
 * <ul>
 *   <li>{@code processKey} (required) — the process definition key to start.</li>
 *   <li>{@code businessKey} (optional) — supports {@code ${var}} interpolation against the
 *       trigger context; defaults to the trigger {@code recordId} so the started instance
 *       is correlatable back to the record.</li>
 *   <li>{@code variables} (optional) — a Map of process variables; String values support
 *       {@code ${var}} interpolation.</li>
 *   <li>{@code title} (optional) — instance title; defaults to "Automation: &lt;processKey&gt;".</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartProcessActionExecutor implements ActionExecutor {

    private final BpmIntegrationService bpmIntegrationService;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("START_PROCESS action requires config");
        }

        String processKey = (String) config.get("processKey");
        if (processKey == null || processKey.isBlank()) {
            throw new IllegalArgumentException("START_PROCESS action requires processKey");
        }

        String businessKey = resolveVariable((String) config.get("businessKey"), context);
        if (businessKey == null || businessKey.isBlank()) {
            Object recordId = context.get("recordId");
            businessKey = recordId != null ? recordId.toString() : null;
        }

        Map<String, Object> variables = resolveVariables(config.get("variables"), context);

        Object titleCfg = config.get("title");
        String title = (titleCfg instanceof String s && !s.isBlank())
                ? resolveVariable(s, context)
                : "Automation: " + processKey;

        log.info("START_PROCESS starting process: key={}, businessKey={}", processKey, businessKey);

        ProcessInstance instance = bpmIntegrationService.startBusinessProcess(
                processKey, businessKey, variables, title);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("processKey", processKey);
        result.put("businessKey", businessKey);
        if (instance != null && instance.getInstanceId() != null) {
            result.put("processInstanceId", instance.getInstanceId());
        }
        return result;
    }

    @Override
    public boolean supports(String actionType) {
        return "start_process".equals(actionType);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveVariables(Object rawVariables, Map<String, Object> context) {
        Map<String, Object> resolved = new HashMap<>();
        if (rawVariables instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String strValue) {
                    value = resolveVariable(strValue, context);
                }
                resolved.put(String.valueOf(entry.getKey()), value);
            }
        }
        return resolved;
    }

    /**
     * Resolve a {@code ${path.to.value}} placeholder against the context (same idiom as
     * {@link ExecuteCommandExecutor}). Non-placeholder strings are returned unchanged;
     * null input returns null.
     */
    private String resolveVariable(String template, Map<String, Object> context) {
        if (template == null) {
            return null;
        }
        if (template.startsWith("${") && template.endsWith("}")) {
            String varName = template.substring(2, template.length() - 1);
            Object resolved = resolvePath(varName, context);
            return resolved != null ? resolved.toString() : null;
        }
        return template;
    }

    private Object resolvePath(String varName, Map<String, Object> context) {
        String[] parts = varName.split("\\.");
        Object current = context;
        for (String part : parts) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(part);
            } else {
                return null;
            }
        }
        return current;
    }
}
