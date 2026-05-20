package com.auraboot.framework.agent.runtime;

import java.util.Map;

/**
 * Deterministic side-effect request produced by the agent reducer.
 */
public record AgentRuntimeEffect(String type, Map<String, Object> payload) {

    public static final String PROCESS_TOOL_USES = "process_tool_uses";
    public static final String EXECUTE_TOOL = "execute_tool";
    public static final String SUSPEND_FOR_CONFIRMATION = "suspend_for_confirmation";
    public static final String HANDOFF = "handoff";
    public static final String CONTINUE_MODEL_CALL = "continue_model_call";
    public static final String COMPLETE_TURN = "complete_turn";
    public static final String FAIL_TURN = "fail_turn";
    public static final String NOOP = "noop";

    public AgentRuntimeEffect {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static AgentRuntimeEffect of(String type) {
        return new AgentRuntimeEffect(type, Map.of());
    }

    public static AgentRuntimeEffect of(String type, Map<String, Object> payload) {
        return new AgentRuntimeEffect(type, payload);
    }
}
