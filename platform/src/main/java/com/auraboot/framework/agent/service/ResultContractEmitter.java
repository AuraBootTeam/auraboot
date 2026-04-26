package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ResultContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a ResultContract from a tool-execution result and emits it as a
 * `result_contract` SSE event on the current chat turn's emitter (see
 * ChatSseContext). Called by ToolLoopService after each dsl_query /
 * dsl_command execution.
 *
 * Hides engine internals behind the contract shape — the frontend renderer
 * at `web-admin/app/plugins/core-aurabot/components-internal/ResultContractView.tsx`
 * dispatches on renderHint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultContractEmitter {

    private static final String EVENT = "result_contract";

    private final ObjectMapper objectMapper;

    /**
     * Build a ResultContract for a dsl_query result and emit it.
     * resultJson is the shape produced by ToolLoopService.executeDslQuery:
     *   { "total": N, "returned": N, "records": [...] }
     */
    public void emitQueryResult(String toolName, AgentToolDefinition toolDef,
                                String resultJson, long durationMs, boolean success) {
        SseEmitter emitter = ChatSseContext.getEmitter();
        if (emitter == null) return;

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

        send(emitter, b.build());
    }

    /**
     * Build a ResultContract for a dsl_command result and emit it.
     * resultJson shape:
     *   { "success": bool, "data": {...}, "message": "..." }
     */
    public void emitCommandResult(String toolName, AgentToolDefinition toolDef,
                                   String resultJson, long durationMs, boolean success,
                                   String errorMessage) {
        SseEmitter emitter = ChatSseContext.getEmitter();
        if (emitter == null) return;

        ResultContract.ResultContractBuilder b = ResultContract.builder()
                .skillCode(toolName)
                .durationMs(durationMs)
                .status(success ? "success" : "failed")
                .actionability(resolveActionability())
                .outputType(success ? "action_proposal" : "text");

        if (!success) {
            b.renderHint("summary").textSummary(errorMessage != null ? errorMessage : "Command failed");
            send(emitter, b.build());
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

        send(emitter, b.build());
    }

    /**
     * Emit a non-executing action proposal for tools that require explicit
     * user confirmation before side effects are allowed.
     */
    public void emitConfirmationRequired(String toolName, AgentToolDefinition toolDef,
                                         Map<String, Object> input, long durationMs) {
        SseEmitter emitter = ChatSseContext.getEmitter();
        if (emitter == null) return;

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

        send(emitter, contract);
    }

    private String resolveActionability() {
        BusinessIntentFrame bif = BifContext.getCurrentBif();
        if (bif != null && bif.getActionability() != null) return bif.getActionability();
        return "read_only";
    }

    private void send(SseEmitter emitter, ResultContract contract) {
        try {
            emitter.send(SseEmitter.event().name(EVENT).data(objectMapper.writeValueAsString(contract)));
        } catch (Exception e) {
            log.debug("Failed to send result_contract SSE event: {}", e.getMessage());
        }
    }
}
