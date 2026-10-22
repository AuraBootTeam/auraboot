package com.auraboot.framework.agent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Event consumed by the agent reducer. Payloads may contain raw tool/provider
 * data; the reducer is responsible for hashing payloads before storing state.
 */
public record AgentRuntimeEvent(
        String type,
        int round,
        String stopReason,
        String toolId,
        String toolName,
        Map<String, Object> payload) {

    public static final String MODEL_RESPONSE_RECEIVED = "model_response";
    public static final String TOOL_USE_REQUESTED = "tool_use";
    public static final String TOOL_RESULT_RECORDED = "tool_result";
    public static final String CONFIRMATION_REQUIRED = "confirmation_required";
    public static final String HANDOFF_REQUESTED = "handoff_requested";
    public static final String TURN_COMPLETED = "turn_completed";
    public static final String TURN_FAILED = "turn_failed";

    public AgentRuntimeEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static AgentRuntimeEvent modelResponse(int round, String stopReason, Map<String, Object> payload) {
        return new AgentRuntimeEvent(MODEL_RESPONSE_RECEIVED, round, stopReason, null, null, payload);
    }

    public static AgentRuntimeEvent toolUseRequested(int round, String toolId, String toolName,
                                                     Map<String, Object> input, boolean requiresConfirmation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", input == null ? Map.of() : input);
        payload.put("requiresConfirmation", requiresConfirmation);
        return new AgentRuntimeEvent(TOOL_USE_REQUESTED, round, null, toolId, toolName, payload);
    }

    public static AgentRuntimeEvent toolResultRecorded(int round, String toolId, String toolName,
                                                       Map<String, Object> result) {
        return new AgentRuntimeEvent(TOOL_RESULT_RECORDED, round, null, toolId, toolName,
                Map.of("result", result == null ? Map.of() : result));
    }

    public static AgentRuntimeEvent confirmationRequired(int round, String toolId, String toolName,
                                                         Map<String, Object> input) {
        return new AgentRuntimeEvent(CONFIRMATION_REQUIRED, round, null, toolId, toolName,
                Map.of("input", input == null ? Map.of() : input));
    }

    public static AgentRuntimeEvent handoffRequested(int round, String targetAgentCode) {
        return new AgentRuntimeEvent(HANDOFF_REQUESTED, round, null, null, "transfer_to_agent",
                Map.of("targetAgentCode", targetAgentCode == null ? "" : targetAgentCode));
    }

    public static AgentRuntimeEvent turnCompleted(int round) {
        return new AgentRuntimeEvent(TURN_COMPLETED, round, null, null, null, Map.of());
    }

    public static AgentRuntimeEvent turnFailed(int round, String errorType) {
        return new AgentRuntimeEvent(TURN_FAILED, round, null, null, null,
                Map.of("errorType", errorType == null ? "" : errorType));
    }

    public static AgentRuntimeEvent turnFailed(int round, AgentErrorFrame errorFrame) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (errorFrame != null) {
            payload.put("errorType", errorFrame.errorClass());
            payload.put("errorFrame", errorFrame.toSnapshotMap());
        }
        return new AgentRuntimeEvent(TURN_FAILED, round, null, null, null, payload);
    }
}
