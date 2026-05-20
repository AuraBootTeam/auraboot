package com.auraboot.framework.agent.runtime.policy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

final class ToolPolicyDecisionBuilder {

    ToolPolicyDecision allow(Map<String, Object> normalizedArgs) {
        return ToolPolicyDecision.allow(normalizedArgs, "allowed");
    }

    ToolPolicyDecision requireUserConfirmation(ToolMetadata metadata,
                                               Map<String, Object> normalizedArgs,
                                               String argsHash) {
        return ToolPolicyDecision.requireUserConfirmation(
                new ToolPolicyDecision.PendingSpec(
                        metadata.getToolName(),
                        metadata.getToolVersion(),
                        normalizedArgs,
                        argsHash,
                        buildPreview(metadata, normalizedArgs),
                        buildIdempotencyKey(metadata, argsHash),
                        Instant.now().plus(30, ChronoUnit.MINUTES),
                        "user_confirmation_required"),
                "user_confirmation_required");
    }

    ToolPolicyDecision requireHumanApproval(ToolMetadata metadata, Map<String, Object> normalizedArgs) {
        return ToolPolicyDecision.requireHumanApproval(
                new ToolPolicyDecision.ApprovalSpec(
                        metadata.getToolName(),
                        metadata.getRiskLevel(),
                        normalizedArgs,
                        "human_approval_required"),
                "human_approval_required");
    }

    ToolPolicyDecision escalateDurable(ToolMetadata metadata,
                                       Map<String, Object> normalizedArgs,
                                       String reasonCode) {
        return ToolPolicyDecision.escalateDurable(
                new ToolPolicyDecision.DurableSpec(
                        metadata.getToolName(),
                        normalizedArgs,
                        "checkpoint_required",
                        "resume_from_last_committed_step",
                        reasonCode),
                reasonCode);
    }

    private String buildPreview(ToolMetadata metadata, Map<String, Object> args) {
        return "Execute " + metadata.getToolName() + " with " + args.size() + " argument(s).";
    }

    private String buildIdempotencyKey(ToolMetadata metadata, String argsHash) {
        return metadata.getToolName() + ":" + metadata.getToolVersion() + ":" + argsHash;
    }
}
