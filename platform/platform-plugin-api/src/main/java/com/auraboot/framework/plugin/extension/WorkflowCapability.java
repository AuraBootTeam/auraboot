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
}
