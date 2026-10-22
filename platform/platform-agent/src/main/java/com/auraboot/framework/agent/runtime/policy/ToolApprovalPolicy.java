package com.auraboot.framework.agent.runtime.policy;

final class ToolApprovalPolicy {

    private final ToolRiskPolicy riskPolicy;

    ToolApprovalPolicy() {
        this(new ToolRiskPolicy());
    }

    ToolApprovalPolicy(ToolRiskPolicy riskPolicy) {
        this.riskPolicy = riskPolicy;
    }

    enum ApprovalDecisionType {
        NONE,
        USER_CONFIRMATION,
        HUMAN_APPROVAL
    }

    record ApprovalDecision(ApprovalDecisionType type, String reasonCode) {

        static ApprovalDecision none() {
            return new ApprovalDecision(ApprovalDecisionType.NONE, null);
        }

        static ApprovalDecision userConfirmation() {
            return new ApprovalDecision(ApprovalDecisionType.USER_CONFIRMATION, "user_confirmation_required");
        }

        static ApprovalDecision humanApproval() {
            return new ApprovalDecision(ApprovalDecisionType.HUMAN_APPROVAL, "human_approval_required");
        }
    }

    ApprovalDecision evaluate(ToolMetadata metadata) {
        if (metadata == null) {
            return ApprovalDecision.none();
        }
        if (metadata.getApprovalRequirement() == ApprovalRequirement.HUMAN_APPROVAL
                || riskPolicy.evaluate(metadata).highRisk()) {
            return ApprovalDecision.humanApproval();
        }
        if (metadata.getApprovalRequirement() == ApprovalRequirement.USER_CONFIRMATION
                || metadata.getEffectType() == ToolEffectType.INTERNAL_WRITE) {
            return ApprovalDecision.userConfirmation();
        }
        return ApprovalDecision.none();
    }
}
