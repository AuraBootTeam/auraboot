package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.authorization.EffectClass;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Idempotency and recovery key for a direct tool execution with side effects.
 */
public record DurableToolExecutionRequest(
        Long tenantId,
        String runPid,
        String taskPid,
        String agentCode,
        String toolName,
        String toolRef,
        String argsHash,
        String toolType,
        Set<EffectClass> requiredEffects,
        Map<String, Object> input) {

    public DurableToolExecutionRequest {
        requiredEffects = requiredEffects == null ? Set.of() : Set.copyOf(requiredEffects);
        input = input == null ? Map.of() : new LinkedHashMap<>(input);
    }

    public DurableToolExecutionRequest(Long tenantId,
                                       String runPid,
                                       String taskPid,
                                       String agentCode,
                                       String toolName,
                                       String toolRef,
                                       String argsHash,
                                       Set<EffectClass> requiredEffects,
                                       Map<String, Object> input) {
        this(tenantId, runPid, taskPid, agentCode, toolName, toolRef, argsHash,
                null, requiredEffects, input);
    }

    public String executionKey() {
        String run = hasText(runPid) ? runPid : "run";
        String tool = hasText(toolRef) ? toolRef : (hasText(toolName) ? toolName : "tool");
        String hash = hasText(argsHash) ? argsHash : "noargs";
        return "agent.tool_execution:" + run + ":" + tool + ":" + hash;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
