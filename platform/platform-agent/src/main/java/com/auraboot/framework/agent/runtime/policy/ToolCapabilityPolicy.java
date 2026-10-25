package com.auraboot.framework.agent.runtime.policy;

import java.util.Set;

final class ToolCapabilityPolicy {

    record CapabilityDecision(boolean allowed, String reasonCode, String userSafeMessage) {

        static CapabilityDecision allow() {
            return new CapabilityDecision(true, "allowed", null);
        }

        static CapabilityDecision deny(String reasonCode, String userSafeMessage) {
            return new CapabilityDecision(false, reasonCode, userSafeMessage);
        }
    }

    CapabilityDecision evaluate(ToolMetadata metadata, ExecutionEnvelope envelope, ToolPolicyActor actor) {
        if (metadata == null) {
            return CapabilityDecision.deny("missing_tool_metadata", "Tool metadata is missing.");
        }
        ExecutionEnvelope effectiveEnvelope = envelope != null ? envelope : ExecutionEnvelope.answerOnly();
        if (!hasRequiredPermissions(metadata, actor)) {
            return CapabilityDecision.deny("missing_permission", "You do not have permission to use this tool.");
        }
        if (!withinCapabilityCeiling(metadata, effectiveEnvelope)) {
            return CapabilityDecision.deny("capability_ceiling_exceeded",
                    "This tool is not available in the current execution envelope.");
        }
        return CapabilityDecision.allow();
    }

    boolean visibleInCatalog(ToolMetadata metadata, ExecutionEnvelope envelope, ToolPolicyActor actor) {
        if (metadata == null) {
            return false;
        }
        ExecutionEnvelope effectiveEnvelope = envelope != null ? envelope : ExecutionEnvelope.answerOnly();
        if (effectiveEnvelope.toolExposure() == ToolExposure.ANSWER_ONLY
                || effectiveEnvelope.capabilityCeiling() == ToolCapabilityCeiling.NO_TOOLS) {
            return false;
        }
        if (!evaluate(metadata, effectiveEnvelope, actor).allowed()) {
            return false;
        }
        ToolEffectType effectType = effectType(metadata);
        if (effectiveEnvelope.toolExposure() == ToolExposure.READ_ONLY_CATALOG
                && effectType != ToolEffectType.INTERNAL_READ
                && effectType != ToolEffectType.NONE) {
            return false;
        }
        return effectiveEnvelope.toolExposure() != ToolExposure.ACTION_PROPOSAL
                || effectType != ToolEffectType.EXTERNAL_ACTION;
    }

    private boolean hasRequiredPermissions(ToolMetadata metadata, ToolPolicyActor actor) {
        Set<String> required = metadata.getRequiredPermissions();
        if (required == null || required.isEmpty()) {
            return true;
        }
        Set<String> available = actor != null ? actor.permissions() : Set.of();
        return available.containsAll(required);
    }

    private boolean withinCapabilityCeiling(ToolMetadata metadata, ExecutionEnvelope envelope) {
        ToolEffectType effectType = effectType(metadata);
        return switch (envelope.capabilityCeiling()) {
            case NO_TOOLS -> effectType == ToolEffectType.NONE;
            case READ_ONLY -> effectType == ToolEffectType.NONE || effectType == ToolEffectType.INTERNAL_READ;
            case WRITE_CAPABLE -> true;
            case PROPOSE_ONLY -> effectType == ToolEffectType.NONE || effectType == ToolEffectType.INTERNAL_READ;
        };
    }

    private ToolEffectType effectType(ToolMetadata metadata) {
        return metadata.getEffectType() != null ? metadata.getEffectType() : ToolEffectType.NONE;
    }
}
