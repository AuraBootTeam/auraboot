package com.auraboot.framework.agent.runtime.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolCapabilityPolicy")
class ToolCapabilityPolicyTest {

    private final ToolCapabilityPolicy policy = new ToolCapabilityPolicy();

    @Test
    @DisplayName("denies tool when actor misses required permission")
    void deniesMissingPermission() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("cmd:crm_customer_update")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .requiredPermissions(Set.of("crm.customer.update"))
                .build();

        ToolCapabilityPolicy.CapabilityDecision decision = policy.evaluate(
                metadata,
                ExecutionEnvelope.writeCatalogWithGate(),
                new ToolPolicyActor(1L, 2L, Set.of("crm.customer.read")));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("missing_permission");
    }

    @Test
    @DisplayName("denies write tool inside read-only capability ceiling")
    void deniesWriteToolInsideReadOnlyCeiling() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("cmd:crm_customer_update")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .build();

        ToolCapabilityPolicy.CapabilityDecision decision = policy.evaluate(
                metadata,
                ExecutionEnvelope.readOnlyCatalog(),
                new ToolPolicyActor(1L, 2L, Set.of()));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("capability_ceiling_exceeded");
    }
}
