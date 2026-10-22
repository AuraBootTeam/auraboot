package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.util.CanonicalJsonHasher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default deterministic reducer for agent runtime events.
 */
@Component
public class DefaultAgentReducer implements AgentReducer {

    @Override
    public Result reduce(AgentExecutionState state, AgentRuntimeEvent event) {
        if (state == null || event == null) {
            return new Result(state, List.of(AgentRuntimeEffect.of(AgentRuntimeEffect.NOOP)));
        }
        Map<String, Object> pending = new LinkedHashMap<>(state.pending());
        int eventCount = intValue(pending.get("eventCount")) + 1;
        pending.put("eventCount", eventCount);
        pending.put("lastEventType", event.type());
        pending.put("lastEventRound", event.round());
        putIfNotBlank(pending, "lastStopReason", event.stopReason());
        putIfNotBlank(pending, "lastToolId", event.toolId());
        putIfNotBlank(pending, "lastToolName", event.toolName());
        String payloadHash = CanonicalJsonHasher.sha256Canonical(event.payload());
        putIfNotBlank(pending, "lastPayloadHash", payloadHash);

        AgentExecutionState next = rehash(state, pending);
        return new Result(next, effectsFor(event));
    }

    private List<AgentRuntimeEffect> effectsFor(AgentRuntimeEvent event) {
        List<AgentRuntimeEffect> effects = new ArrayList<>();
        switch (event.type()) {
            case AgentRuntimeEvent.MODEL_RESPONSE_RECEIVED -> {
                if ("tool_use".equals(event.stopReason())) {
                    effects.add(AgentRuntimeEffect.of(AgentRuntimeEffect.PROCESS_TOOL_USES));
                } else {
                    effects.add(AgentRuntimeEffect.of(AgentRuntimeEffect.COMPLETE_TURN));
                }
            }
            case AgentRuntimeEvent.TOOL_USE_REQUESTED -> {
                if (Boolean.TRUE.equals(event.payload().get("requiresConfirmation"))) {
                    effects.add(AgentRuntimeEffect.of(AgentRuntimeEffect.SUSPEND_FOR_CONFIRMATION));
                } else {
                    effects.add(AgentRuntimeEffect.of(AgentRuntimeEffect.EXECUTE_TOOL));
                }
            }
            case AgentRuntimeEvent.CONFIRMATION_REQUIRED ->
                    effects.add(AgentRuntimeEffect.of(AgentRuntimeEffect.SUSPEND_FOR_CONFIRMATION));
            case AgentRuntimeEvent.HANDOFF_REQUESTED ->
                    effects.add(AgentRuntimeEffect.of(AgentRuntimeEffect.HANDOFF));
            case AgentRuntimeEvent.TOOL_RESULT_RECORDED ->
                    effects.add(AgentRuntimeEffect.of(AgentRuntimeEffect.CONTINUE_MODEL_CALL));
            case AgentRuntimeEvent.TURN_COMPLETED ->
                    effects.add(AgentRuntimeEffect.of(AgentRuntimeEffect.COMPLETE_TURN));
            case AgentRuntimeEvent.TURN_FAILED ->
                    effects.add(AgentRuntimeEffect.of(AgentRuntimeEffect.FAIL_TURN));
            default -> effects.add(AgentRuntimeEffect.of(AgentRuntimeEffect.NOOP));
        }
        return effects;
    }

    private AgentExecutionState rehash(AgentExecutionState state, Map<String, Object> pending) {
        AgentExecutionState withoutHash = new AgentExecutionState(
                state.schemaVersion(),
                state.executionKind(),
                state.turnId(),
                state.runPid(),
                state.taskPid(),
                state.tenantId(),
                state.userId(),
                state.agentCode(),
                state.sessionId(),
                state.providerCode(),
                state.model(),
                state.round(),
                state.toolChoice(),
                state.context(),
                state.tools(),
                pending,
                null);
        String stateHash = CanonicalJsonHasher.sha256Canonical(withoutHash.toSnapshotMap(false));
        return new AgentExecutionState(
                withoutHash.schemaVersion(),
                withoutHash.executionKind(),
                withoutHash.turnId(),
                withoutHash.runPid(),
                withoutHash.taskPid(),
                withoutHash.tenantId(),
                withoutHash.userId(),
                withoutHash.agentCode(),
                withoutHash.sessionId(),
                withoutHash.providerCode(),
                withoutHash.model(),
                withoutHash.round(),
                withoutHash.toolChoice(),
                withoutHash.context(),
                withoutHash.tools(),
                withoutHash.pending(),
                stateHash);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
