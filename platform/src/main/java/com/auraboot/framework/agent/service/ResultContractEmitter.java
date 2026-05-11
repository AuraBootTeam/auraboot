package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.agent.observability.AgentRuntimeObservabilityService;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.ResponseSinkContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ResultContract} from a tool-execution result and pushes it
 * through the current turn's {@link ResponseSink} (resolved via
 * {@link ResponseSinkContext}). Called by {@code ToolLoopService} after each
 * {@code dsl_query} / {@code dsl_command} execution.
 *
 * <p>Phase C.3b migration (2026-04-30, Q-C3.4=α): formerly read the SSE
 * emitter directly through {@code ChatSseContext.getEmitter()}. The
 * indirection is now sink-typed — {@code ResponseSinkContext.get()} returns
 * a transport-agnostic {@link ResponseSink}, and the SSE adapter
 * ({@code SseResponseSink.onResultContract}) preserves byte-for-byte
 * equivalence with the prior emitter pipeline. Non-chat callers (tests,
 * ad-hoc skill invocations) still see no context bound and the emitter
 * silently no-ops.
 *
 * <p>Hides engine internals behind the contract shape — the frontend renderer
 * at {@code web-admin/app/plugins/core-aurabot/components-internal/ResultContractView.tsx}
 * dispatches on {@code renderHint}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultContractEmitter {

    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AgentRuntimeObservabilityService observabilityService;

    /**
     * Build a ResultContract for a dsl_query result and emit it.
     * resultJson is the shape produced by ToolLoopService.executeDslQuery:
     *   { "total": N, "returned": N, "records": [...] }
     */
    public void emitQueryResult(String toolName, AgentToolDefinition toolDef,
                                String resultJson, long durationMs, boolean success) {
        ResponseSink sink = ResponseSinkContext.get();
        if (sink == null) {
            recordResultContract("structured_result", "none", success ? "success" : "failed", false);
            return;
        }

        ResultContract.ResultContractBuilder b = ResultContract.builder()
                .skillCode(toolName)
                .durationMs(durationMs)
                .status(success ? "success" : "failed")
                .actionability(resolveActionability())
                .outputType("structured_result");

        try {
            if (success && resultJson != null && !resultJson.startsWith("Error")) {
                Map<String, Object> parsed = objectMapper.readValue(resultJson, Map.class);
                Object records = parsed.get("records");
                if (records instanceof List<?> list && !list.isEmpty()) {
                    List<Map<String, Object>> table = new ArrayList<>();
                    for (Object row : list) {
                        if (row instanceof Map<?, ?> m) {
                            Map<String, Object> safeRow = new LinkedHashMap<>();
                            m.forEach((k, v) -> safeRow.put(String.valueOf(k), v));
                            table.add(safeRow);
                        }
                    }
                    b.renderHint("table").table(table);
                    Object total = parsed.get("total");
                    if (total != null) {
                        b.textSummary(total + " total, " + table.size() + " shown");
                    }
                } else {
                    b.renderHint("summary").textSummary("0 results");
                }
            } else {
                b.renderHint("summary").textSummary(success ? "(no data)" : "Query failed");
            }
        } catch (Exception e) {
            log.debug("Failed to build ResultContract for query {}: {}", toolName, e.getMessage());
            b.renderHint("summary").textSummary(success ? "(unparseable result)" : "Query failed");
        }

        emit(sink, b.build());
    }

    /**
     * Build a ResultContract for a dsl_command result and emit it.
     * resultJson shape:
     *   { "success": bool, "data": {...}, "message": "..." }
     */
    public void emitCommandResult(String toolName, AgentToolDefinition toolDef,
                                   String resultJson, long durationMs, boolean success,
                                   String errorMessage) {
        ResponseSink sink = ResponseSinkContext.get();
        if (sink == null) {
            recordResultContract(success ? "action_proposal" : "text", "none", success ? "success" : "failed", false);
            return;
        }

        ResultContract.ResultContractBuilder b = ResultContract.builder()
                .skillCode(toolName)
                .durationMs(durationMs)
                .status(success ? "success" : "failed")
                .actionability(resolveActionability())
                .outputType(success ? "action_proposal" : "text");

        if (!success) {
            b.renderHint("summary").textSummary(errorMessage != null ? errorMessage : "Command failed");
            emit(sink, b.build());
            return;
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(resultJson, Map.class);
            Object data = parsed.get("data");
            if (data instanceof Map<?, ?> m && !m.isEmpty()) {
                Map<String, Object> dataMap = new LinkedHashMap<>();
                m.forEach((k, v) -> dataMap.put(String.valueOf(k), v));
                b.renderHint("card").data(dataMap);
            } else {
                b.renderHint("summary");
            }
            Object msg = parsed.get("message");
            if (msg != null) b.textSummary(String.valueOf(msg));
        } catch (Exception e) {
            log.debug("Failed to build ResultContract for command {}: {}", toolName, e.getMessage());
            b.renderHint("summary").textSummary("Command succeeded");
        }

        emit(sink, b.build());
    }

    /**
     * Build a ResultContract for provider-backed tools such as platform/custom/MCP.
     */
    public void emitProviderResult(String toolName, AgentToolDefinition toolDef,
                                   String resultJson, long durationMs, boolean success) {
        ResponseSink sink = ResponseSinkContext.get();
        if (sink == null) {
            recordResultContract(success ? "structured_result" : "text", "none", success ? "success" : "failed", false);
            return;
        }

        ResultContract.ResultContractBuilder b = ResultContract.builder()
                .skillCode(toolName)
                .durationMs(durationMs)
                .status(success ? "success" : "failed")
                .actionability(resolveActionability())
                .outputType(success ? "structured_result" : "text");

        try {
            Map<String, Object> parsed = resultJson == null || resultJson.startsWith("Error")
                    ? Map.of()
                    : objectMapper.readValue(resultJson, Map.class);
            if (!success) {
                Object error = parsed.get("error");
                b.renderHint("summary")
                        .textSummary(error != null ? String.valueOf(error) : "Provider tool failed");
            } else if (parsed.get("records") instanceof List<?> records) {
                b.renderHint("table")
                        .table(toTable(records))
                        .textSummary(records.size() + " rows");
            } else if (parsed.get("models") instanceof List<?> models) {
                b.renderHint("table")
                        .table(toTable(models))
                        .textSummary(models.size() + " models");
            } else {
                b.renderHint("card").data(parsed);
                Object message = parsed.get("message");
                if (message != null) {
                    b.textSummary(String.valueOf(message));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to build ResultContract for provider tool {}: {}", toolName, e.getMessage());
            b.renderHint("summary").textSummary(success ? "Provider tool succeeded" : "Provider tool failed");
        }

        emit(sink, b.build());
    }

    private List<Map<String, Object>> toTable(List<?> rows) {
        List<Map<String, Object>> table = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> m) {
                Map<String, Object> safeRow = new LinkedHashMap<>();
                m.forEach((k, v) -> safeRow.put(String.valueOf(k), v));
                table.add(safeRow);
            }
        }
        return table;
    }

    /**
     * Emit a non-executing action proposal for tools that require explicit
     * user confirmation before side effects are allowed.
     */
    public void emitConfirmationRequired(String toolName, AgentToolDefinition toolDef,
                                         Map<String, Object> input, long durationMs) {
        ResponseSink sink = ResponseSinkContext.get();
        if (sink == null) {
            recordResultContract("action_proposal", "card", "partial_success", false);
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolCode", toolName);
        data.put("riskLevel", toolDef != null ? toolDef.getRiskLevel() : null);
        data.put("confirmationPolicy", toolDef != null ? toolDef.getConfirmationPolicy() : null);
        data.put("prefillInput", input != null ? input : Map.of());

        ResultContract.SuggestedAction action = ResultContract.SuggestedAction.builder()
                .label("Confirm and execute")
                .skillCode(toolName)
                .prefillInput(input != null ? input : Map.of())
                .build();

        ResultContract contract = ResultContract.builder()
                .skillCode(toolName)
                .durationMs(durationMs)
                .status("partial_success")
                .actionability("propose")
                .outputType("action_proposal")
                .renderHint("card")
                .data(data)
                .textSummary("Confirmation required before execution")
                .suggestedActions(List.of(action))
                .canContinueFrom(true)
                .build();

        emit(sink, contract);
    }

    private void emit(ResponseSink sink, ResultContract contract) {
        sink.onResultContract(contract);
        recordResultContract(
                contract.getOutputType(),
                contract.getRenderHint(),
                contract.getStatus(),
                true);
    }

    private void recordResultContract(String outputType, String renderHint, String status, boolean emitted) {
        if (observabilityService != null) {
            observabilityService.recordResultContract(outputType, renderHint, status, emitted);
        }
    }

    private String resolveActionability() {
        BusinessIntentFrame bif = BifContext.getCurrentBif();
        if (bif != null && bif.getActionability() != null) return bif.getActionability();
        return "read_only";
    }
}
