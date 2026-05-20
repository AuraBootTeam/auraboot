package com.auraboot.framework.agent.runtime.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolPolicyDecisionBuilder")
class ToolPolicyDecisionBuilderTest {

    private final ToolPolicyDecisionBuilder builder = new ToolPolicyDecisionBuilder();

    @Test
    @DisplayName("builds user confirmation decision with preview idempotency and expiry")
    void buildsUserConfirmationDecision() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("cmd:crm_customer_create")
                .toolVersion("v2")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .build();

        ToolPolicyDecision decision = builder.requireUserConfirmation(
                metadata,
                Map.of("name", "Acme"),
                "abc123");

        assertThat(decision.type()).isEqualTo(ToolPolicyDecision.Type.REQUIRE_USER_CONFIRMATION);
        assertThat(decision.pendingSpec()).isNotNull();
        assertThat(decision.pendingSpec().preview()).isEqualTo("Execute cmd:crm_customer_create with 1 argument(s).");
        assertThat(decision.pendingSpec().idempotencyKey()).isEqualTo("cmd:crm_customer_create:v2:abc123");
        assertThat(decision.pendingSpec().expiresAt()).isNotNull();
    }
}
