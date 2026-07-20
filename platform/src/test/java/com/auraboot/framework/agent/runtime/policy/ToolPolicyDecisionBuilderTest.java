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

    @Test
    @DisplayName("the model-facing alias is shown as the command, not as cmd_underscore_soup")
    void previewRestoresTheCommandFromTheAlias() {
        // Tool names handed to a model cannot contain a colon, so the namespace separator is
        // encoded as the first underscore. This preview is the sentence someone reads before
        // authorising a write, and it used to read "Execute cmd_crm_create_account" — the alias,
        // not the command. The existing case above only covered names that kept their colon,
        // which is why nothing caught it.
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("cmd_crm_create_account")
                .toolVersion("v1")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .build();

        ToolPolicyDecision decision = new ToolPolicyDecisionBuilder().requireUserConfirmation(
                metadata, Map.of("crm_acc_name", "Acme"), "hash1");

        assertThat(decision.pendingSpec().preview())
                .isEqualTo("Execute crm:create_account with 1 argument(s).");
    }
}
