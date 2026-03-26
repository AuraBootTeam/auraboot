package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Executor for EXECUTE_COMMAND action type
 * Executes a defined Command (CommandDefinition)
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecuteCommandExecutor implements ActionExecutor {

    private final CommandExecutor commandExecutor;

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        Map<String, Object> config = action.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("EXECUTE_COMMAND action requires config");
        }

        String commandCode = (String) config.get("commandCode");
        if (commandCode == null || commandCode.isBlank()) {
            throw new IllegalArgumentException("EXECUTE_COMMAND action requires commandCode");
        }

        // Get command parameters
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) config.get("params");
        if (params == null) {
            params = new HashMap<>();
        }

        // Add context values to params
        String recordId = (String) context.get("recordId");
        if (recordId != null && !params.containsKey("pid")) {
            params.put("pid", recordId);
        }

        // Process parameter values
        Map<String, Object> processedParams = processParams(params, context);

        log.info("Executing command: code={}, params={}", commandCode, processedParams.keySet());

        // Execute command
        CommandExecuteRequest executeRequest = new CommandExecuteRequest();
        executeRequest.setPayload(processedParams);
        executeRequest.setTargetRecordId(recordId);

        CommandExecuteResult result = commandExecutor.execute(commandCode, executeRequest);

        return Map.of(
                "success", true,
                "commandCode", commandCode,
                "result", result.getData() != null ? result.getData() : Map.of()
        );
    }

    @Override
    public boolean supports(String actionType) {
        return "execute_command".equals(actionType);
    }

    private Map<String, Object> processParams(Map<String, Object> params, Map<String, Object> context) {
        Map<String, Object> processed = new HashMap<>();

        for (Map.Entry<String, Object> entry : params.entrySet()) {
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
