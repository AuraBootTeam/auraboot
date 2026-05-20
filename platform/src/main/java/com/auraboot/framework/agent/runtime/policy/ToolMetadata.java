package com.auraboot.framework.agent.runtime.policy;

import lombok.Builder;
import lombok.Value;

import java.util.Set;

@Value
@Builder(toBuilder = true)
public class ToolMetadata {
    String toolName;
    @Builder.Default
    String toolVersion = "v1";
    @Builder.Default
    ToolEffectType effectType = ToolEffectType.NONE;
    @Builder.Default
    String riskLevel = "L0";
    @Builder.Default
    Set<String> requiredPermissions = Set.of();
    boolean supportsPreview;
    boolean supportsIdempotency;
    boolean reversible;
    Integer batchLimit;
    boolean externalSideEffect;
    @Builder.Default
    DurabilityRequirement durabilityRequirement = DurabilityRequirement.NONE;
    @Builder.Default
    ApprovalRequirement approvalRequirement = ApprovalRequirement.NONE;
    @Builder.Default
    String auditLevel = "standard";
    String schemaHash;
    @Builder.Default
    ToolMetadataTrustLevel metadataTrustLevel = ToolMetadataTrustLevel.INFERRED;
    @Builder.Default
    String policyVersion = "v1";
}
