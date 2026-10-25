package com.auraboot.framework.agent.runtime.policy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolPolicyDecision(
        Type type,
        Map<String, Object> sanitizedArgs,
        PendingSpec pendingSpec,
        ApprovalSpec approvalSpec,
        DurableSpec durableSpec,
        String reasonCode,
        String userSafeMessage) {

    public enum Type {
        ALLOW,
        REQUIRE_USER_CONFIRMATION,
        REQUIRE_HUMAN_APPROVAL,
        ESCALATE_DURABLE_WORKFLOW,
        DENY
    }

    public record PendingSpec(String toolName,
                              String toolVersion,
                              Map<String, Object> normalizedArgs,
                              String argsHash,
                              String preview,
                              String idempotencyKey,
                              Instant expiresAt,
                              String reasonCode) {
        public PendingSpec {
            normalizedArgs = normalizedArgs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(normalizedArgs));
        }
    }

    public record ApprovalSpec(String toolName,
                               String riskLevel,
                               Map<String, Object> normalizedArgs,
                               String reasonCode) {
        public ApprovalSpec {
            normalizedArgs = normalizedArgs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(normalizedArgs));
        }
    }

    public record DurableSpec(String toolName,
                              Map<String, Object> normalizedArgs,
                              String checkpointPolicy,
                              String recoveryPolicy,
                              String reasonCode) {
        public DurableSpec {
            normalizedArgs = normalizedArgs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(normalizedArgs));
        }
    }

    public ToolPolicyDecision {
        sanitizedArgs = sanitizedArgs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(sanitizedArgs));
    }

    public static ToolPolicyDecision allow(Map<String, Object> sanitizedArgs, String reasonCode) {
        return new ToolPolicyDecision(Type.ALLOW, sanitizedArgs, null, null, null, reasonCode, null);
    }

    public static ToolPolicyDecision requireUserConfirmation(PendingSpec pendingSpec, String reasonCode) {
        return new ToolPolicyDecision(Type.REQUIRE_USER_CONFIRMATION,
                pendingSpec != null ? pendingSpec.normalizedArgs() : Map.of(),
                pendingSpec, null, null, reasonCode, null);
    }

    public static ToolPolicyDecision requireHumanApproval(ApprovalSpec approvalSpec, String reasonCode) {
        return new ToolPolicyDecision(Type.REQUIRE_HUMAN_APPROVAL,
                approvalSpec != null ? approvalSpec.normalizedArgs() : Map.of(),
                null, approvalSpec, null, reasonCode, null);
    }

    public static ToolPolicyDecision escalateDurable(DurableSpec durableSpec, String reasonCode) {
        return new ToolPolicyDecision(Type.ESCALATE_DURABLE_WORKFLOW,
                durableSpec != null ? durableSpec.normalizedArgs() : Map.of(),
                null, null, durableSpec, reasonCode, null);
    }

    public static ToolPolicyDecision deny(String reasonCode, String userSafeMessage) {
        return new ToolPolicyDecision(Type.DENY, Map.of(), null, null, null, reasonCode, userSafeMessage);
    }
}
