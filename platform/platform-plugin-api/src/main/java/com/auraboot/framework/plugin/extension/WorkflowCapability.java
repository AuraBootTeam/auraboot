package com.auraboot.framework.plugin.extension;

import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Narrow operation port between the platform and an installed workflow product.
 * Operation tokens are versioned by the provider; missing operations fail closed.
 */
public interface WorkflowCapability {
    String capabilityId();
    Set<String> operations();
    WorkflowResult execute(String operation, WorkflowRequest request);

    /** JSON object values may be null; keep a defensive, immutable top-level snapshot. */
    private static Map<String, Object> snapshotPayload(Map<String, Object> payload) {
        if (payload == null) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        payload.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "Workflow payload keys must not be null"), value));
        return Collections.unmodifiableMap(copy);
    }

    record WorkflowRequest(Long tenantId, Long actorUserId, Map<String, Object> payload) {
        public WorkflowRequest {
            payload = snapshotPayload(payload);
        }
    }

    record WorkflowResult(Map<String, Object> payload) {
        public WorkflowResult {
            payload = snapshotPayload(payload);
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
            this.payload = snapshotPayload(payload);
        }

        public Map<String, Object> payload() {
            return payload;
        }
    }
}
