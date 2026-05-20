package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compensates durable tool executions through an explicitly configured provider tool.
 *
 * <p>The original tool request must include {@code compensationToolRef} (or
 * {@code compensation_tool_ref}) plus optional {@code compensationArgs}. The
 * handler never infers a rollback target from the failed tool name.
 */
@Component
public class ProviderToolCompensationHandler implements DurableToolCompensationHandler {

    private final ToolProviderRegistry toolProviderRegistry;
    private final ObjectMapper objectMapper;

    public ProviderToolCompensationHandler(ToolProviderRegistry toolProviderRegistry,
                                           ObjectMapper objectMapper) {
        this.toolProviderRegistry = toolProviderRegistry;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public boolean supports(DurableToolExecutionRecord record) {
        return record != null
                && record.request() != null
                && record.request().tenantId() != null
                && hasText(compensationToolRef(record.request().input()));
    }

    @Override
    public DurableToolCompensationResult compensate(DurableToolExecutionRecord record) {
        if (!supports(record)) {
            return new DurableToolCompensationResult(false, null, "compensation tool is not configured");
        }
        String toolRef = compensationToolRef(record.request().input());
        Map<String, Object> args = compensationArgs(record.request().input());
        ProviderExecutionResult result = toolProviderRegistry.execute(record.request().tenantId(), toolRef, args);
        if (result == null) {
            return new DurableToolCompensationResult(false, null, "provider returned no compensation result");
        }
        String rawResult = rawResult(toolRef, result);
        if (result.isSuccess()) {
            return new DurableToolCompensationResult(true, rawResult, "compensated by " + toolRef);
        }
        String message = result.getErrorMessage() == null || result.getErrorMessage().isBlank()
                ? "provider compensation failed"
                : result.getErrorMessage();
        return new DurableToolCompensationResult(false, rawResult, message);
    }

    private String rawResult(String toolRef, ProviderExecutionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.isSuccess());
        payload.put("provider", toolRef);
        payload.put("data", result.getData() == null ? Map.of() : result.getData());
        if (result.getErrorMessage() != null) {
            payload.put("errorMessage", result.getErrorMessage());
        }
        payload.put("durationMs", result.getDurationMs());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"success\":" + result.isSuccess() + "}";
        }
    }

    private String compensationToolRef(Map<String, Object> input) {
        Object value = firstNonNull(input == null ? null : input.get("compensationToolRef"),
                input == null ? null : input.get("compensation_tool_ref"));
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compensationArgs(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Object args = firstNonNull(input.get("compensationArgs"), input.get("compensation_args"));
        if (args instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
