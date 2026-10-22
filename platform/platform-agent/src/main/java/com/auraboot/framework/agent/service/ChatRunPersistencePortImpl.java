package com.auraboot.framework.agent.service;

import com.auraboot.framework.aurabot.service.ChatRunPersistencePort;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.agent.trace.mapper.AiTraceMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise-AI implementation of {@link ChatRunPersistencePort}.
 * Persists AuraBot chat sessions as ab_agent_run records so they appear
 * alongside ACP Agent runs on the /aurabot/runs page.
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRunPersistencePortImpl implements ChatRunPersistencePort {

    private static final String TABLE_NAME = "ab_agent_run";
    private static final String AGENT_ID = "aurabot";
    private final DynamicDataMapper dynamicDataMapper;
    private final AiTraceMapper aiTraceMapper;
    private final ObjectMapper objectMapper;
    /** In-memory accumulation of tool calls per run, serialized on completeRun. */
    private final Map<String, List<Map<String, Object>>> toolCallsMap = new ConcurrentHashMap<>();

    /** Track run start times for duration calculation. */
    private final Map<String, LocalDateTime> startTimeMap = new ConcurrentHashMap<>();

    /** Track AuraBot run metadata until completion. */
    private final Map<String, Map<String, Object>> runMetadataMap = new ConcurrentHashMap<>();

    @Override
    public String createRun(Long tenantId, String sessionId, String model, String userMessage) {
        try {
            String runPid = UniqueIdGenerator.generate();
            LocalDateTime now = LocalDateTime.now();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "aurabot");
            metadata.put("sessionId", sessionId);
            metadata.put("userMessage", userMessage);

            Map<String, Object> run = new HashMap<>();
            run.put("pid", runPid);
            run.put("tenant_id", tenantId);
            run.put("task_id", runPid); // self-referencing — no actual task for chat
            run.put("agent_id", AGENT_ID);
            run.put("run_status", "running");
            run.put("run_model", model);
            run.put("started_at", now);
            run.put("input_tokens", 0);
            run.put("output_tokens", 0);
            run.put("total_cost", 0);
            run.put("metadata", objectMapper.writeValueAsString(metadata));
            run.put("created_at", now);
            run.put("updated_at", now);
            dynamicDataMapper.insert(TABLE_NAME, run);

            startTimeMap.put(runPid, now);
            toolCallsMap.put(runPid, Collections.synchronizedList(new ArrayList<>()));
            runMetadataMap.put(runPid, metadata);

            log.debug("Created chat run record: pid={}, tenantId={}, sessionId={}, model={}",
                    runPid, tenantId, sessionId, model);
            return runPid;
        } catch (Exception e) {
            // CATCH: non-transactional persistence — failure should not break the chat flow
            log.warn("Failed to create chat run record: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void recordToolCall(String runPid, String toolName, Object input, Object output, boolean success) {
        if (runPid == null) return;
        List<Map<String, Object>> calls = toolCallsMap.get(runPid);
        if (calls == null) return;

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool", toolName);
        call.put("input", input);
        call.put("output", output);
        call.put("success", success);
        call.put("timestamp", LocalDateTime.now().toString());
        calls.add(call);
    }

    @Override
    public void completeRun(String runPid, boolean success, int inputTokens, int outputTokens,
                            double cost, String finalResponse, String errorMessage, String traceId) {
        if (runPid == null) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startedAt = startTimeMap.remove(runPid);
            long durationMs = startedAt != null ? ChronoUnit.MILLIS.between(startedAt, now) : 0;

            Map<String, Object> update = new HashMap<>();
            update.put("run_status", success ? "success" : "failed");
            update.put("completed_at", now);
            update.put("duration_ms", durationMs);
            update.put("input_tokens", inputTokens);
            update.put("output_tokens", outputTokens);
            update.put("total_cost", cost);
            update.put("updated_at", now);

            if (errorMessage != null) {
                update.put("error_message", errorMessage);
            }

            // Serialize tool calls to JSON
            List<Map<String, Object>> calls = toolCallsMap.remove(runPid);
            if (calls != null && !calls.isEmpty()) {
                update.put("tool_calls", objectMapper.writeValueAsString(calls));
            }

            // Store final response in messages column
            if (finalResponse != null && !finalResponse.isBlank()) {
                update.put("messages", finalResponse);
            }

            Map<String, Object> metadata = runMetadataMap.remove(runPid);
            if (metadata != null) {
                metadata.put("traceId", traceId);
                metadata.put("finalResponsePreview", abbreviate(finalResponse));
                metadata.put("errorMessage", errorMessage);
                update.put("metadata", objectMapper.writeValueAsString(metadata));
            }

            dynamicDataMapper.update(TABLE_NAME, update, Map.of("pid", runPid));
            finishTraceIfNeeded(traceId, success, finalResponse, errorMessage, durationMs);
            log.debug("Completed chat run: pid={}, success={}, traceId={}, duration={}ms, tokens={}/{}",
                    runPid, success, traceId, durationMs, inputTokens, outputTokens);
        } catch (Exception e) {
            // CATCH: non-transactional persistence — failure should not break the chat flow
            log.warn("Failed to complete chat run record {}: {}", runPid, e.getMessage(), e);
        } finally {
            // Ensure cleanup even on error
            startTimeMap.remove(runPid);
            toolCallsMap.remove(runPid);
            runMetadataMap.remove(runPid);
        }
    }

    private String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }

    private void finishTraceIfNeeded(String traceId, boolean success, String finalResponse,
                                     String errorMessage, long durationMs) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            java.time.Instant endTime = java.time.Instant.now();
            if (success) {
                aiTraceMapper.finishTraceSuccess(
                        traceId,
                        abbreviate(finalResponse),
                        "success",
                        endTime,
                        durationMs);
            } else {
                aiTraceMapper.finishTraceError(
                        traceId,
                        errorMessage != null ? errorMessage : "AuraBot run failed",
                        endTime,
                        durationMs);
            }
        } catch (Exception e) {
            log.warn("Failed to finalize trace {} from chat run: {}", traceId, e.getMessage(), e);
        }
    }
}
