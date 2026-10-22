package com.auraboot.framework.agent.runtime;

import java.util.Map;

/**
 * Storage boundary for suspended chat tool continuations.
 */
public interface PendingToolStore {

    void storePending(String turnId, PendingToolSnapshot pendingTool);

    PendingToolSnapshot consumePendingForOwner(String turnId, Long tenantId, Long userId);

    default PendingToolExecutionClaim claimExecution(PendingToolSnapshot pendingTool) {
        return PendingToolExecutionClaim.acquired(executionKey(pendingTool));
    }

    default void completeExecution(String executionKey, Map<String, Object> result) {
    }

    default void completeExecution(PendingToolSnapshot pendingTool, String executionKey, Map<String, Object> result) {
        completeExecution(executionKey, result);
    }

    default void failExecution(String executionKey, Map<String, Object> result, String errorMessage) {
    }

    default void failExecution(PendingToolSnapshot pendingTool,
                               String executionKey,
                               Map<String, Object> result,
                               String errorMessage) {
        failExecution(executionKey, result, errorMessage);
    }

    static String executionKey(PendingToolSnapshot pendingTool) {
        if (pendingTool == null) {
            return "pending-tool:missing";
        }
        if (hasText(pendingTool.getIdempotencyKey())) {
            return pendingTool.getIdempotencyKey();
        }
        String turnId = hasText(pendingTool.getTurnId()) ? pendingTool.getTurnId() : "turn";
        String toolId = hasText(pendingTool.getToolId()) ? pendingTool.getToolId() : pendingTool.getToolName();
        String argsHash = hasText(pendingTool.getArgsHash()) ? pendingTool.getArgsHash() : "noargs";
        return "pending-tool:" + turnId + ":" + (hasText(toolId) ? toolId : "tool") + ":" + argsHash;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
