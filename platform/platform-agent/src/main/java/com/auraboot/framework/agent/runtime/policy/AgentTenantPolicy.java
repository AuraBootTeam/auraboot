package com.auraboot.framework.agent.runtime.policy;

import java.util.List;

/**
 * Tenant-level execution envelope boundary resolved before a model call.
 */
public record AgentTenantPolicy(
        ToolCapabilityCeiling capabilityCeiling,
        ToolExposure toolExposure,
        DurabilityPreference durabilityPreference) {

    public static AgentTenantPolicy defaults() {
        return new AgentTenantPolicy(null, null, null);
    }

    public static AgentTenantPolicy fromCatalog(List<ToolMetadata> visibleTools) {
        if (visibleTools == null || visibleTools.isEmpty()) {
            return new AgentTenantPolicy(
                    ToolCapabilityCeiling.NO_TOOLS,
                    ToolExposure.ANSWER_ONLY,
                    DurabilityPreference.NONE);
        }
        boolean hasRead = false;
        boolean hasWrite = false;
        boolean allowsDurable = false;
        for (ToolMetadata tool : visibleTools) {
            if (tool == null) {
                continue;
            }
            ToolEffectType effectType = tool.getEffectType() != null
                    ? tool.getEffectType()
                    : ToolEffectType.NONE;
            if (effectType == ToolEffectType.INTERNAL_WRITE
                    || effectType == ToolEffectType.EXTERNAL_ACTION) {
                hasWrite = true;
            }
            if (effectType == ToolEffectType.INTERNAL_READ || effectType == ToolEffectType.NONE) {
                hasRead = true;
            }
            if (tool.getDurabilityRequirement() == DurabilityRequirement.REQUIRED
                    || tool.getDurabilityRequirement() == DurabilityRequirement.ALLOWED) {
                allowsDurable = true;
            }
        }
        if (hasWrite) {
            return new AgentTenantPolicy(
                    ToolCapabilityCeiling.WRITE_CAPABLE,
                    ToolExposure.WRITE_CATALOG_WITH_GATE,
                    DurabilityPreference.ALLOWED);
        }
        if (hasRead) {
            return new AgentTenantPolicy(
                    ToolCapabilityCeiling.READ_ONLY,
                    ToolExposure.READ_ONLY_CATALOG,
                    allowsDurable ? DurabilityPreference.ALLOWED : DurabilityPreference.NONE);
        }
        return new AgentTenantPolicy(
                ToolCapabilityCeiling.NO_TOOLS,
                ToolExposure.ANSWER_ONLY,
                DurabilityPreference.NONE);
    }
}
