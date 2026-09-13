package com.auraboot.framework.plugin.extension;

import java.util.Map;
import java.util.Set;

/**
 * Narrow operation port between the platform and an installed workflow product.
 * Operation tokens are versioned by the provider; missing operations fail closed.
 */
public interface WorkflowCapability {
    String capabilityId();
    Set<String> operations();
    WorkflowResult execute(String operation, WorkflowRequest request);

    record WorkflowRequest(Long tenantId, Long actorUserId, Map<String, Object> payload) {
        public WorkflowRequest {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    record WorkflowResult(Map<String, Object> payload) {
        public WorkflowResult {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
        public static WorkflowResult empty() { return new WorkflowResult(Map.of()); }
    }

    /**
     * A workflow operation failed after producing caller-visible partial state.
     *
     * <p>The exception preserves failure semantics while carrying a narrow,
     * serializable payload such as completed/failed action results. Platform
     * callers must still treat the operation as failed.</p>
     */
    final class WorkflowExecutionException extends RuntimeException {
        private final Map<String, Object> payload;

        public WorkflowExecutionException(String message, Throwable cause, Map<String, Object> payload) {
            super(message, cause);
            this.payload = payload == null ? Map.of() : Map.copyOf(payload);
        }

        public Map<String, Object> payload() {
            return payload;
        }
    }
}
