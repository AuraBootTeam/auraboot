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

    /**
     * The sentence a person reads before authorising an action, so it carries the command as the
     * product names it rather than the LLM-safe alias. The alias exists because model tool names
     * cannot contain a colon; showing it here asked someone to approve "cmd_crm_create_account".
     */
    private String buildPreview(ToolMetadata metadata, Map<String, Object> args) {
        return "Execute " + readableToolName(metadata.getToolName()) + " with " + args.size() + " argument(s).";
    }

    private String readableToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return String.valueOf(toolName);
        }
        // A name that already carries a colon is in the form the product uses; leave it alone.
        // Both shapes reach here — `cmd:crm_customer_create` from callers that kept the separator,
        // and `cmd_crm_create_account` from the model-facing alias where a colon is not allowed.
        if (toolName.indexOf(':') >= 0) {
            return toolName;
        }
        String name = toolName;
        for (String prefix : new String[] {"cmd__", "nq__", "builtin__", "cmd_", "nq_", "builtin_"}) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
                break;
            }
        }
        // In the alias the command's namespace colon was encoded as the first underscore.
        int separator = name.indexOf('_');
        return separator > 0 && separator < name.length() - 1
                ? name.substring(0, separator) + ":" + name.substring(separator + 1)
                : name;
    }

    private String buildIdempotencyKey(ToolMetadata metadata, String argsHash) {
        return metadata.getToolName() + ":" + metadata.getToolVersion() + ":" + argsHash;
    }
}
