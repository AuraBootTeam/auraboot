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
                && hasText(compensationSpec(record.request().input()).toolRef());
    }

    @Override
    public DurableToolCompensationResult compensate(DurableToolExecutionRecord record) {
        if (!supports(record)) {
            return new DurableToolCompensationResult(false, null, "compensation tool is not configured");
        }
        BusinessCompensationSpec spec = compensationSpec(record.request().input());
        ProviderExecutionResult result = toolProviderRegistry.execute(
                record.request().tenantId(), spec.toolRef(), spec.argsWithIdempotency());
        if (result == null) {
            return new DurableToolCompensationResult(false, null, "provider returned no compensation result");
        }
        String rawResult = rawResult(record, spec, result);
        if (result.isSuccess()) {
            return new DurableToolCompensationResult(true, rawResult, "compensated by " + spec.toolRef());
        }
        String message = result.getErrorMessage() == null || result.getErrorMessage().isBlank()
                ? "provider compensation failed"
                : result.getErrorMessage();
        return new DurableToolCompensationResult(false, rawResult, message);
    }

    private String rawResult(DurableToolExecutionRecord record,
                             BusinessCompensationSpec spec,
                             ProviderExecutionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.isSuccess());
        payload.put("provider", spec.toolRef());
        payload.put("executionKey", record.executionKey());
        if (hasText(spec.idempotencyKey())) {
            payload.put("idempotencyKey", spec.idempotencyKey());
        }
        if (hasText(record.compensationReason())) {
            payload.put("compensationReason", record.compensationReason());
        }
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

    private BusinessCompensationSpec compensationSpec(Map<String, Object> input) {
        Map<String, Object> nested = nestedCompensation(input);
        String toolRef = firstText(
                nested.get("toolRef"),
                nested.get("tool_ref"),
                nested.get("toolName"),
                nested.get("tool_name"),
                input == null ? null : input.get("compensationToolRef"),
                input == null ? null : input.get("compensation_tool_ref"));
        Map<String, Object> args = compensationArgs(input, nested);
        String idempotencyKey = firstText(
                nested.get("idempotencyKey"),
                nested.get("idempotency_key"),
                nested.get("clientRequestId"),
                nested.get("client_request_id"),
                input == null ? null : input.get("compensationIdempotencyKey"),
                input == null ? null : input.get("compensation_idempotency_key"));
        return new BusinessCompensationSpec(toolRef, args, idempotencyKey);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedCompensation(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Object value = firstNonNull(input.get("compensation"), input.get("compensation_spec"));
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compensationArgs(Map<String, Object> input,
                                                 Map<String, Object> nested) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Object args = firstNonNull(
                nested.get("args"),
                nested.get("arguments"),
                nested.get("input"),
                input.get("compensationArgs"),
                input.get("compensation_args"));
        if (args instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Object... values) {
        Object value = firstNonNull(values);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record BusinessCompensationSpec(String toolRef,
                                            Map<String, Object> args,
                                            String idempotencyKey) {

        private BusinessCompensationSpec {
            args = args == null ? Map.of() : Map.copyOf(args);
        }

        private Map<String, Object> argsWithIdempotency() {
            if (idempotencyKey == null || idempotencyKey.isBlank()
                    || args.containsKey("idempotencyKey")
                    || args.containsKey("idempotency_key")) {
                return args;
            }
            Map<String, Object> withKey = new LinkedHashMap<>(args);
            withKey.put("idempotencyKey", idempotencyKey);
            return withKey;
        }
    }
}
