package com.auraboot.framework.agent.runtime.policy;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Component
public class ToolMetadataRegistry {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public ToolMetadata from(ToolDefinition definition, ToolMetadataTrustLevel trustLevel) {
        if (definition == null) {
            throw new IllegalArgumentException("Tool definition is required");
        }
        String toolName = firstNonBlank(definition.getToolCode(), definition.getToolName(), definition.getSourceCode());
        ToolEffectType effectType = inferEffectType(toolName, definition.getToolType());
        ToolMetadataTrustLevel effectiveTrust = trustLevel != null ? trustLevel : ToolMetadataTrustLevel.INFERRED;
        boolean external = effectType == ToolEffectType.EXTERNAL_ACTION;
        ApprovalRequirement approval = inferApprovalRequirement(
                effectType,
                effectiveTrust,
                definition.isRequiresApproval(),
                definition.isRequiresConfirmation(),
                definition.getRiskLevel());
        DurabilityRequirement durability = inferDurabilityRequirement(effectType, effectiveTrust);

        return ToolMetadata.builder()
                .toolName(toolName)
                .toolVersion("v1")
                .effectType(effectType)
                .riskLevel(normalizeRisk(definition.getRiskLevel()))
                .requiredPermissions(definition.getRequiredPermissions())
                .supportsPreview(definition.isRequiresConfirmation() || approval == ApprovalRequirement.USER_CONFIRMATION)
                .supportsIdempotency(false)
                .externalSideEffect(external)
                .approvalRequirement(approval)
                .durabilityRequirement(durability)
                .metadataTrustLevel(effectiveTrust)
                .policyVersion("v1")
                .schemaHash(hash(definition.getParameterSchema()))
                .build();
    }

    public ToolMetadata from(AgentToolDefinition definition, ToolMetadataTrustLevel trustLevel) {
        if (definition == null) {
            throw new IllegalArgumentException("Agent tool definition is required");
        }
        ToolEffectType effectType = inferEffectType(definition.getName(), definition.getToolType());
        ToolMetadataTrustLevel effectiveTrust = trustLevel != null ? trustLevel : ToolMetadataTrustLevel.INFERRED;
        boolean external = effectType == ToolEffectType.EXTERNAL_ACTION;
        ApprovalRequirement approval = inferApprovalRequirement(
                effectType,
                effectiveTrust,
                definition.isRequiresApproval(),
                definition.isRequiresConfirmation(),
                definition.getRiskLevel());
        DurabilityRequirement durability = inferDurabilityRequirement(effectType, effectiveTrust);

        return ToolMetadata.builder()
                .toolName(definition.getName())
                .toolVersion("v1")
                .effectType(effectType)
                .riskLevel(normalizeRisk(definition.getRiskLevel()))
                .requiredPermissions(definition.getRequiredPermissions())
                .supportsPreview(definition.isRequiresConfirmation() || approval == ApprovalRequirement.USER_CONFIRMATION)
                .supportsIdempotency(false)
                .externalSideEffect(external)
                .approvalRequirement(approval)
                .durabilityRequirement(durability)
                .metadataTrustLevel(effectiveTrust)
                .policyVersion("v1")
                .schemaHash(hash(definition.getInputSchema()))
                .build();
    }

    private ToolEffectType inferEffectType(String toolName, String toolType) {
        String name = lower(toolName);
        String type = lower(toolType);
        if ("llm_native".equals(type)) {
            return ToolEffectType.NONE;
        }
        if ("dsl_query".equals(type) || startsWithAny(name, "nq:", "nq_", "list:", "list_", "get:", "get_")
                || "platform_execute_sql".equals(name) || "platform.execute_sql".equals(name)) {
            return ToolEffectType.INTERNAL_READ;
        }
        if ("mcp".equals(type) || "api_call".equals(type) || startsWithAny(name, "mcp:", "mcp_", "api:", "api_")) {
            return ToolEffectType.EXTERNAL_ACTION;
        }
        if ("dsl_command".equals(type) || startsWithAny(name, "cmd:", "cmd_")) {
            return ToolEffectType.INTERNAL_WRITE;
        }
        if ("platform".equals(type) && (name.contains("create") || name.contains("update")
                || name.contains("delete") || name.contains("execute"))) {
            return ToolEffectType.INTERNAL_WRITE;
        }
        if ("aurabot_skill".equals(type) || "aurabot_skill".equalsIgnoreCase(String.valueOf(toolType))) {
            return ToolEffectType.INTERNAL_WRITE;
        }
        return ToolEffectType.INTERNAL_WRITE;
    }

    private ApprovalRequirement inferApprovalRequirement(ToolEffectType effectType,
                                                         ToolMetadataTrustLevel trustLevel,
                                                         boolean requiresApproval,
                                                         boolean requiresConfirmation,
                                                         String riskLevel) {
        if (requiresApproval || highRisk(riskLevel)) {
            return ApprovalRequirement.HUMAN_APPROVAL;
        }
        if (effectType == ToolEffectType.EXTERNAL_ACTION && !trustedExecutableMetadata(trustLevel)) {
            return ApprovalRequirement.HUMAN_APPROVAL;
        }
        if (requiresConfirmation || effectType == ToolEffectType.INTERNAL_WRITE) {
            return ApprovalRequirement.USER_CONFIRMATION;
        }
        return ApprovalRequirement.NONE;
    }

    private DurabilityRequirement inferDurabilityRequirement(ToolEffectType effectType,
                                                             ToolMetadataTrustLevel trustLevel) {
        if (effectType == ToolEffectType.EXTERNAL_ACTION) {
            return DurabilityRequirement.REQUIRED;
        }
        if (!trustedExecutableMetadata(trustLevel)) {
            return DurabilityRequirement.ALLOWED;
        }
        return DurabilityRequirement.NONE;
    }

    private boolean trustedExecutableMetadata(ToolMetadataTrustLevel trustLevel) {
        return trustLevel == ToolMetadataTrustLevel.VERIFIED || trustLevel == ToolMetadataTrustLevel.ADMIN_APPROVED;
    }

    private boolean highRisk(String riskLevel) {
        String normalized = normalizeRisk(riskLevel);
        return "L3".equals(normalized) || "L4".equals(normalized);
    }

    private String normalizeRisk(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return "L0";
        }
        return riskLevel.trim().toUpperCase(Locale.ROOT);
    }

    private boolean startsWithAny(String value, String... prefixes) {
        if (value == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String hash(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(schema);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }
}
