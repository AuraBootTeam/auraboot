package com.auraboot.framework.agent.runtime.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolDurabilityPolicy")
class ToolDurabilityPolicyTest {

    private final ToolDurabilityPolicy policy = new ToolDurabilityPolicy();

    @Test
    @DisplayName("requires durable workflow for external side effects")
    void externalSideEffectRequiresDurableWorkflow() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("mcp:send_email")
                .effectType(ToolEffectType.EXTERNAL_ACTION)
                .externalSideEffect(true)
                .build();

        ToolDurabilityPolicy.DurabilityDecision decision = policy.evaluate(
                metadata,
                ExecutionEnvelope.writeCatalogWithGate());

        assertThat(decision.required()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("external_side_effect_requires_durable_workflow");
    }

    @Test
    @DisplayName("requires durable workflow when envelope requires durability")
    void envelopeRequiredDurabilityRequiresDurableWorkflow() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("cmd:crm_customer_batch_update")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .build();
        ExecutionEnvelope envelope = new ExecutionEnvelope(
                LifecycleEntry.NEW_TURN,
                InitialExecutionMode.DURABLE_WORKFLOW_ENTRY,
                ToolCapabilityCeiling.WRITE_CAPABLE,
                ToolExposure.WRITE_CATALOG_WITH_GATE,
                DurabilityPreference.REQUIRED);

        ToolDurabilityPolicy.DurabilityDecision decision = policy.evaluate(metadata, envelope);

        assertThat(decision.required()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("durability_required");
    }
}
