package com.auraboot.framework.agent.runtime.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolApprovalPolicy")
class ToolApprovalPolicyTest {

    private final ToolApprovalPolicy policy = new ToolApprovalPolicy();

    @Test
    @DisplayName("requires human approval for high-risk tools")
    void highRiskToolRequiresHumanApproval() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("cmd:finance_adjustment")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .riskLevel("l3")
                .build();

        ToolApprovalPolicy.ApprovalDecision decision = policy.evaluate(metadata);

        assertThat(decision.type()).isEqualTo(ToolApprovalPolicy.ApprovalDecisionType.HUMAN_APPROVAL);
        assertThat(decision.reasonCode()).isEqualTo("human_approval_required");
    }

    @Test
    @DisplayName("requires user confirmation for internal writes")
    void internalWriteRequiresUserConfirmation() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("cmd:crm_customer_create")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .riskLevel("L1")
                .build();

        ToolApprovalPolicy.ApprovalDecision decision = policy.evaluate(metadata);

        assertThat(decision.type()).isEqualTo(ToolApprovalPolicy.ApprovalDecisionType.USER_CONFIRMATION);
        assertThat(decision.reasonCode()).isEqualTo("user_confirmation_required");
    }
}
