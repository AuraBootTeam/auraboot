package com.auraboot.framework.agent.runtime.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ToolPolicyEngine {

    private final ToolCapabilityPolicy capabilityPolicy = new ToolCapabilityPolicy();
    private final ToolArgumentPolicy argumentPolicy = new ToolArgumentPolicy();
    private final ToolRiskPolicy riskPolicy = new ToolRiskPolicy();
    private final ToolDurabilityPolicy durabilityPolicy = new ToolDurabilityPolicy(riskPolicy);
    private final ToolApprovalPolicy approvalPolicy = new ToolApprovalPolicy(riskPolicy);
    private final ToolPolicyDecisionBuilder decisionBuilder = new ToolPolicyDecisionBuilder();

    public ToolPolicyDecision evaluate(ToolPolicyCall call,
                                       ExecutionEnvelope envelope,
                                       ToolMetadata metadata,
                                       ToolPolicyActor actor) {
        ExecutionEnvelope effectiveEnvelope = envelope != null ? envelope : ExecutionEnvelope.answerOnly();
        if (call == null || call.toolName() == null || call.toolName().isBlank()) {
            return ToolPolicyDecision.deny("missing_tool_call", "Tool call is missing.");
        }
        if (metadata == null) {
            return ToolPolicyDecision.deny("missing_tool_metadata", "Tool metadata is missing.");
        }
        ToolCapabilityPolicy.CapabilityDecision capabilityDecision =
                capabilityPolicy.evaluate(metadata, effectiveEnvelope, actor);
        if (!capabilityDecision.allowed()) {
            return ToolPolicyDecision.deny(capabilityDecision.reasonCode(), capabilityDecision.userSafeMessage());
        }

        Map<String, Object> normalizedArgs = argumentPolicy.normalize(call.args());
        ToolDurabilityPolicy.DurabilityDecision durabilityDecision =
                durabilityPolicy.evaluate(metadata, effectiveEnvelope);
        if (durabilityDecision.required()) {
            return decisionBuilder.escalateDurable(metadata, normalizedArgs, durabilityDecision.reasonCode());
        }
        ToolApprovalPolicy.ApprovalDecision approvalDecision = approvalPolicy.evaluate(metadata);
        if (approvalDecision.type() == ToolApprovalPolicy.ApprovalDecisionType.HUMAN_APPROVAL) {
            return decisionBuilder.requireHumanApproval(metadata, normalizedArgs);
        }
        if (approvalDecision.type() == ToolApprovalPolicy.ApprovalDecisionType.USER_CONFIRMATION) {
            String argsHash = argumentPolicy.hash(normalizedArgs);
            return decisionBuilder.requireUserConfirmation(metadata, normalizedArgs, argsHash);
        }
        return decisionBuilder.allow(normalizedArgs);
    }

    public List<ToolMetadata> filterToolCatalog(List<ToolMetadata> tools,
                                                ExecutionEnvelope envelope,
                                                ToolPolicyActor actor) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        ExecutionEnvelope effectiveEnvelope = envelope != null ? envelope : ExecutionEnvelope.answerOnly();
        if (effectiveEnvelope.toolExposure() == ToolExposure.ANSWER_ONLY
                || effectiveEnvelope.capabilityCeiling() == ToolCapabilityCeiling.NO_TOOLS) {
            return List.of();
        }
        List<ToolMetadata> filtered = new ArrayList<>();
        for (ToolMetadata tool : tools) {
            if (capabilityPolicy.visibleInCatalog(tool, effectiveEnvelope, actor)) {
                filtered.add(tool);
            }
        }
        filtered.sort(Comparator.comparing(ToolMetadata::getToolName,
                Comparator.nullsLast(String::compareTo)));
        return List.copyOf(filtered);
    }
}
