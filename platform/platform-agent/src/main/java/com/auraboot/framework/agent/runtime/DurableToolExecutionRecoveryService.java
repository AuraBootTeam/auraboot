package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.service.ActionRecorder;
import com.auraboot.framework.common.util.LogSanitizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recovers failed direct provider-side tool executions that are safe to retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DurableToolExecutionRecoveryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DurableToolExecutionLedger ledger;
    private final ToolProviderRegistry toolProviderRegistry;
    private final ObjectMapper objectMapper;
    private final ActionRecorder actionRecorder;

    @Value("${agent.tool-execution.recovery.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(cron = "${agent.tool-execution.recovery.cron:0 * * * * *}")
    public int processDue() {
        return processDue(batchSize);
    }

    int processDue(int limit) {
        int processed = 0;
        for (DurableToolExecutionRecord record : ledger.findRecoverable(limit)) {
            if (recover(record)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean recover(DurableToolExecutionRecord record) {
        DurableToolExecutionRequest request = record == null ? null : record.request();
        if (!canRetry(record, request)) {
            ledger.markCompensationRequired(record, compensationReason(record, request));
            return true;
        }
        if (!ledger.claimRetry(record)) {
            return false;
        }
        try {
            ProviderExecutionResult providerResult = toolProviderRegistry.execute(
                    request.tenantId(),
                    request.toolRef(),
                    request.input());
            String rawResult = serializeProviderResult(providerResult);
            if (providerResult != null && providerResult.isSuccess()) {
                ledger.complete(request, record.executionKey(), rawResult);
                actionRecorder.recordProviderAction(
                        request.tenantId(),
                        request.runPid(),
                        request.toolRef(),
                        toolDefinition(request),
                        request.input(),
                        parseResult(rawResult),
                        null,
                        request.requiredEffects());
            } else {
                String error = providerResult == null
                        ? "provider returned no result"
                        : providerResult.getErrorMessage();
                ledger.fail(request, record.executionKey(), rawResult, error);
            }
            return true;
        } catch (Exception e) {
            String error = safeError(e);
            ledger.fail(request, record.executionKey(), "Error: " + error, error);
            log.warn("Durable tool execution retry failed: key={}, error={}",
                    LogSanitizer.safe(record.executionKey()), error);
            return true;
        }
    }

    private boolean canRetry(DurableToolExecutionRecord record, DurableToolExecutionRequest request) {
        if (record == null || request == null) {
            return false;
        }
        if (!record.retryable() || record.attemptCount() >= record.maxAttempts()) {
            return false;
        }
        String toolRef = request.toolRef();
        String toolType = request.toolType();
        return hasText(toolRef)
                && (toolRef.startsWith("platform.")
                || toolRef.startsWith("custom:")
                || toolRef.startsWith("mcp:")
                || "platform".equals(toolType)
                || "custom".equals(toolType)
                || "mcp".equals(toolType)
                || "built_in".equals(toolType));
    }

    private String compensationReason(DurableToolExecutionRecord record, DurableToolExecutionRequest request) {
        if (record == null || request == null) {
            return "missing retry request snapshot";
        }
        if (!record.retryable()) {
            return "not retryable: missing external idempotency key";
        }
        if (record.attemptCount() >= record.maxAttempts()) {
            return "retry attempts exhausted";
        }
        return "unsupported recovery tool type";
    }

    private String serializeProviderResult(ProviderExecutionResult providerResult) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", providerResult != null && providerResult.isSuccess());
        if (providerResult != null && providerResult.getData() != null) {
            response.putAll(providerResult.getData());
        }
        if (providerResult != null && providerResult.getErrorMessage() != null) {
            response.put("error", providerResult.getErrorMessage());
        }
        if (providerResult != null) {
            response.put("durationMs", providerResult.getDurationMs());
        }
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"Unable to serialize provider retry result\"}";
        }
    }

    private Map<String, Object> parseResult(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawResult, MAP_TYPE);
        } catch (Exception e) {
            return Map.of("rawResult", rawResult);
        }
    }

    private AgentToolDefinition toolDefinition(DurableToolExecutionRequest request) {
        return AgentToolDefinition.builder()
                .name(request.toolName())
                .sourceCode(request.toolRef())
                .toolType(request.toolType() != null ? request.toolType() : "provider")
                .riskLevel("L3")
                .build();
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
