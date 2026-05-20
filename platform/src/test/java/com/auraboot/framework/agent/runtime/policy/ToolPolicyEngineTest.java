package com.auraboot.framework.agent.runtime.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolPolicyEngine")
class ToolPolicyEngineTest {

    private final ToolPolicyEngine policyEngine = new ToolPolicyEngine();

    @Test
    @DisplayName("allows verified read tools inside read-only envelope")
    void allowsReadToolInsideReadOnlyEnvelope() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("nq:crm_customer_stats")
                .toolVersion("v1")
                .effectType(ToolEffectType.INTERNAL_READ)
                .riskLevel("L0")
                .metadataTrustLevel(ToolMetadataTrustLevel.VERIFIED)
                .approvalRequirement(ApprovalRequirement.NONE)
                .durabilityRequirement(DurabilityRequirement.NONE)
                .build();

        ToolPolicyDecision decision = policyEngine.evaluate(
                new ToolPolicyCall("nq:crm_customer_stats", Map.of("industry", "software")),
                ExecutionEnvelope.readOnlyCatalog(),
                metadata,
                new ToolPolicyActor(1L, 2L, Set.of()));

        assertThat(decision.type()).isEqualTo(ToolPolicyDecision.Type.ALLOW);
        assertThat(decision.sanitizedArgs()).containsEntry("industry", "software");
    }

    @Test
    @DisplayName("denies write tools inside read-only envelope")
    void deniesWriteToolInsideReadOnlyEnvelope() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("cmd:crm_customer_delete")
                .toolVersion("v1")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .riskLevel("L2")
                .metadataTrustLevel(ToolMetadataTrustLevel.VERIFIED)
                .approvalRequirement(ApprovalRequirement.USER_CONFIRMATION)
                .durabilityRequirement(DurabilityRequirement.NONE)
                .build();

        ToolPolicyDecision decision = policyEngine.evaluate(
                new ToolPolicyCall("cmd:crm_customer_delete", Map.of("pid", "C-1")),
                ExecutionEnvelope.readOnlyCatalog(),
                metadata,
                new ToolPolicyActor(1L, 2L, Set.of()));

        assertThat(decision.type()).isEqualTo(ToolPolicyDecision.Type.DENY);
        assertThat(decision.reasonCode()).isEqualTo("capability_ceiling_exceeded");
    }

    @Test
    @DisplayName("turns simple write tools into user confirmation decisions")
    void writeToolRequiresUserConfirmation() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("cmd:crm_customer_create")
                .toolVersion("v1")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .riskLevel("L1")
                .supportsPreview(true)
                .supportsIdempotency(true)
                .metadataTrustLevel(ToolMetadataTrustLevel.ADMIN_APPROVED)
                .approvalRequirement(ApprovalRequirement.USER_CONFIRMATION)
                .durabilityRequirement(DurabilityRequirement.NONE)
                .build();

        ToolPolicyDecision decision = policyEngine.evaluate(
                new ToolPolicyCall("cmd:crm_customer_create", Map.of("name", "Acme")),
                ExecutionEnvelope.writeCatalogWithGate(),
                metadata,
                new ToolPolicyActor(1L, 2L, Set.of()));

        assertThat(decision.type()).isEqualTo(ToolPolicyDecision.Type.REQUIRE_USER_CONFIRMATION);
        assertThat(decision.pendingSpec()).isNotNull();
        assertThat(decision.pendingSpec().argsHash()).isNotBlank();
        assertThat(decision.pendingSpec().idempotencyKey()).isNotBlank();
        assertThat(decision.pendingSpec().toolName()).isEqualTo("cmd:crm_customer_create");
    }

    @Test
    @DisplayName("escalates external side effects to durable workflow")
    void externalSideEffectEscalatesToDurableWorkflow() {
        ToolMetadata metadata = ToolMetadata.builder()
                .toolName("mcp:send_email")
                .toolVersion("v1")
                .effectType(ToolEffectType.EXTERNAL_ACTION)
                .riskLevel("L2")
                .externalSideEffect(true)
                .metadataTrustLevel(ToolMetadataTrustLevel.ADMIN_APPROVED)
                .approvalRequirement(ApprovalRequirement.HUMAN_APPROVAL)
                .durabilityRequirement(DurabilityRequirement.REQUIRED)
                .build();

        ToolPolicyDecision decision = policyEngine.evaluate(
                new ToolPolicyCall("mcp:send_email", Map.of("to", "ops@example.com")),
                ExecutionEnvelope.writeCatalogWithGate(),
                metadata,
                new ToolPolicyActor(1L, 2L, Set.of()));

        assertThat(decision.type()).isEqualTo(ToolPolicyDecision.Type.ESCALATE_DURABLE_WORKFLOW);
        assertThat(decision.durableSpec()).isNotNull();
        assertThat(decision.reasonCode()).isEqualTo("external_side_effect_requires_durable_workflow");
    }

    @Test
    @DisplayName("filters tool catalog before model calls")
    void filtersToolCatalogBeforeModelCalls() {
        ToolMetadata read = ToolMetadata.builder()
                .toolName("nq:crm_customer_stats")
                .effectType(ToolEffectType.INTERNAL_READ)
                .metadataTrustLevel(ToolMetadataTrustLevel.VERIFIED)
                .build();
        ToolMetadata write = ToolMetadata.builder()
                .toolName("cmd:crm_customer_delete")
                .effectType(ToolEffectType.INTERNAL_WRITE)
                .metadataTrustLevel(ToolMetadataTrustLevel.VERIFIED)
                .build();

        List<ToolMetadata> filtered = policyEngine.filterToolCatalog(
                List.of(read, write),
                ExecutionEnvelope.readOnlyCatalog(),
                new ToolPolicyActor(1L, 2L, Set.of()));

        assertThat(filtered).extracting(ToolMetadata::getToolName)
                .containsExactly("nq:crm_customer_stats");
    }
}
