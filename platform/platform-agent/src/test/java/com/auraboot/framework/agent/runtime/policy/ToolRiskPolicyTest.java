package com.auraboot.framework.agent.runtime.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolRiskPolicy")
class ToolRiskPolicyTest {

    private final ToolRiskPolicy policy = new ToolRiskPolicy();

    @Test
    @DisplayName("classifies L3 and L4 risk levels as high risk")
    void classifiesHighRiskLevels() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("cmd:finance_adjustment")
                .riskLevel("l4")
                .build();

        ToolRiskPolicy.RiskDecision decision = policy.evaluate(metadata);

        assertThat(decision.highRisk()).isTrue();
        assertThat(decision.normalizedRiskLevel()).isEqualTo("L4");
    }

    @Test
    @DisplayName("classifies explicit external side effects")
    void classifiesExternalSideEffects() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("mcp:send_email")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .externalSideEffect(true)
                .build();

        ToolRiskPolicy.RiskDecision decision = policy.evaluate(metadata);

        assertThat(decision.externalSideEffect()).isTrue();
    }
}
