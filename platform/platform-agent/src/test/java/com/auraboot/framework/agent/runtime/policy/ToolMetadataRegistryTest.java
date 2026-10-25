package com.auraboot.framework.agent.runtime.policy;

import com.auraboot.framework.agent.provider.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolMetadataRegistry")
class ToolMetadataRegistryTest {

    private final ToolMetadataRegistry registry = new ToolMetadataRegistry();

    @Test
    void builtInEffectsUseCanonicalSourceRatherThanSanitizedReadLikeNames() {
        for (String source : java.util.List.of("nq:customer_stats", "list:customer", "get:customer")) {
            var provider = ToolDefinition.builder().toolCode("wire_tool").toolType("built_in")
                    .sourceCode(source).build();
            var agent = com.auraboot.framework.agent.dto.AgentToolDefinition.builder()
                    .name("wire_tool").toolType("built_in").sourceCode(source).build();
            for (var metadata : java.util.List.of(registry.from(provider, ToolMetadataTrustLevel.VERIFIED),
                    registry.from(agent, ToolMetadataTrustLevel.VERIFIED))) {
                assertThat(metadata.getEffectType()).isEqualTo(ToolEffectType.INTERNAL_READ);
                assertThat(metadata.getApprovalRequirement()).isEqualTo(ApprovalRequirement.NONE);
            }
        }
        for (String source : java.util.List.of("cmd:customer_update", "custom:unknown", "nq_customer_stats", "")) {
            var agent = com.auraboot.framework.agent.dto.AgentToolDefinition.builder()
                    .name("nq_customer_stats").toolType("built_in").sourceCode(source).build();
            var metadata = registry.from(agent, ToolMetadataTrustLevel.VERIFIED);
            assertThat(metadata.getEffectType()).isEqualTo(ToolEffectType.INTERNAL_WRITE);
            assertThat(metadata.getApprovalRequirement()).isEqualTo(ApprovalRequirement.USER_CONFIRMATION);
        }
        for (String source : java.util.List.of("mcp:send_email", "api:update_customer")) {
            var agent = com.auraboot.framework.agent.dto.AgentToolDefinition.builder()
                    .name("nq_customer_stats").toolType("built_in").sourceCode(source).build();
            assertThat(registry.from(agent, ToolMetadataTrustLevel.PROVIDER_DECLARED).getEffectType())
                    .isEqualTo(ToolEffectType.EXTERNAL_ACTION);
        }
    }

    @Test
    void authoritativeMutationAndExternalTypesCannotBeDisguisedByReadLikeNames() {
        for (String prefix : java.util.List.of("get_", "list_", "nq_")) {
            for (String type : java.util.List.of("DSL_COMMAND", "API_CALL", "MCP")) {
                var expected = "DSL_COMMAND".equals(type) ? ToolEffectType.INTERNAL_WRITE : ToolEffectType.EXTERNAL_ACTION;
                var provider = ToolDefinition.builder().toolCode(prefix + "customer_update").toolType(type)
                        .sourceCode("nq:customer_stats").build();
                var agent = com.auraboot.framework.agent.dto.AgentToolDefinition.builder()
                        .name(prefix + "customer_update").toolType(type).sourceCode("nq:customer_stats").build();
                assertThat(registry.from(provider, ToolMetadataTrustLevel.VERIFIED).getEffectType()).isEqualTo(expected);
                assertThat(registry.from(agent, ToolMetadataTrustLevel.VERIFIED).getEffectType()).isEqualTo(expected);
            }
        }
    }

    @Test
    void formDraftHasNoBusinessSideEffectsButOtherPlatformToolsRemainWrites() {
        for (String name : java.util.List.of("platform.fill_form", "platform_fill_form")) {
            ToolMetadata metadata = registry.from(ToolDefinition.builder()
                    .toolCode(name).toolName(name).toolType("platform").riskLevel("L1")
                    .requiresConfirmation(false).build(), ToolMetadataTrustLevel.VERIFIED);
            assertThat(metadata.getEffectType()).isEqualTo(ToolEffectType.NONE);
            assertThat(metadata.getApprovalRequirement()).isEqualTo(ApprovalRequirement.NONE);
        }
        assertThat(registry.from(ToolDefinition.builder().toolCode("platform.update_record")
                .toolType("platform").build(), ToolMetadataTrustLevel.VERIFIED).getEffectType())
                .isEqualTo(ToolEffectType.INTERNAL_WRITE);
    }

    @Test
    void referenceResolutionIsReadOnlyWithoutAllowingUnknownPlatformWrites() {
        for (String name : java.util.List.of("platform.resolve_reference", "platform_resolve_reference")) {
            var metadata = registry.from(ToolDefinition.builder().toolCode(name).toolType("platform")
                    .riskLevel("L0").build(), ToolMetadataTrustLevel.VERIFIED);
            assertThat(metadata.getEffectType()).isEqualTo(ToolEffectType.INTERNAL_READ);
            assertThat(metadata.getApprovalRequirement()).isEqualTo(ApprovalRequirement.NONE);
            assertThat(registry.from(com.auraboot.framework.agent.dto.AgentToolDefinition.builder()
                    .name(name).toolType("platform").riskLevel("L0").build(), ToolMetadataTrustLevel.VERIFIED)
                    .getEffectType()).isEqualTo(ToolEffectType.INTERNAL_READ);
        }
        assertThat(registry.from(ToolDefinition.builder().toolCode("platform.resolve_reference_and_update")
                .toolType("platform").riskLevel("L0").build(), ToolMetadataTrustLevel.VERIFIED).getEffectType())
                .isEqualTo(ToolEffectType.INTERNAL_WRITE);
    }

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
        assertThat(metadata.getPolicyVersion()).isEqualTo(RiskScale.VERSION);
        assertThat(metadata.getApprovalRequirement()).isEqualTo(ApprovalRequirement.NONE);
        assertThat(metadata.getDurabilityRequirement()).isEqualTo(DurabilityRequirement.NONE);
    }

    @Test
    @DisplayName("normalizes legacy risk aliases before approval routing")
    void normalizesLegacyAliases() {
        ToolDefinition highRisk = ToolDefinition.builder()
                .toolCode("cmd:delete_account")
                .toolType("dsl_command")
                .riskLevel("critical")
                .build();

        ToolMetadata metadata = registry.from(highRisk, ToolMetadataTrustLevel.VERIFIED);

        assertThat(metadata.getRiskLevel()).isEqualTo("L4");
        assertThat(metadata.getApprovalRequirement()).isEqualTo(ApprovalRequirement.HUMAN_APPROVAL);
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
