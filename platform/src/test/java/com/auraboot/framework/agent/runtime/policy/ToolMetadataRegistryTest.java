package com.auraboot.framework.agent.runtime.policy;

import com.auraboot.framework.agent.provider.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolMetadataRegistry")
class ToolMetadataRegistryTest {

    private final ToolMetadataRegistry registry = new ToolMetadataRegistry();

    @Test
    @DisplayName("maps platform-owned query tools to verified read metadata")
    void mapsVerifiedReadToolMetadata() {
        ToolDefinition query = ToolDefinition.builder()
                .toolCode("nq:crm_customer_stats")
                .toolName("Customer stats")
                .toolType("dsl_query")
                .sourceCode("crm_customer_stats")
                .riskLevel("L0")
                .requiresApproval(false)
                .requiresConfirmation(false)
                .build();

        ToolMetadata metadata = registry.from(query, ToolMetadataTrustLevel.VERIFIED);

        assertThat(metadata.getToolName()).isEqualTo("nq:crm_customer_stats");
        assertThat(metadata.getEffectType()).isEqualTo(ToolEffectType.INTERNAL_READ);
        assertThat(metadata.getMetadataTrustLevel()).isEqualTo(ToolMetadataTrustLevel.VERIFIED);
        assertThat(metadata.getApprovalRequirement()).isEqualTo(ApprovalRequirement.NONE);
        assertThat(metadata.getDurabilityRequirement()).isEqualTo(DurabilityRequirement.NONE);
    }

    @Test
    @DisplayName("treats provider-declared external tools conservatively")
    void mapsProviderDeclaredExternalToolsConservatively() {
        ToolDefinition external = ToolDefinition.builder()
                .toolCode("mcp:send_email")
                .toolName("Send email")
                .toolType("mcp")
                .sourceCode("send_email")
                .riskLevel("L1")
                .requiresApproval(false)
                .requiresConfirmation(false)
                .build();

        ToolMetadata metadata = registry.from(external, ToolMetadataTrustLevel.PROVIDER_DECLARED);

        assertThat(metadata.getEffectType()).isEqualTo(ToolEffectType.EXTERNAL_ACTION);
        assertThat(metadata.isExternalSideEffect()).isTrue();
        assertThat(metadata.getMetadataTrustLevel()).isEqualTo(ToolMetadataTrustLevel.PROVIDER_DECLARED);
        assertThat(metadata.getApprovalRequirement()).isEqualTo(ApprovalRequirement.HUMAN_APPROVAL);
        assertThat(metadata.getDurabilityRequirement()).isEqualTo(DurabilityRequirement.REQUIRED);
    }
}
