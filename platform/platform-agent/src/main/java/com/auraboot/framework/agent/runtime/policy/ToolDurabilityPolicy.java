package com.auraboot.framework.agent.runtime.policy;

final class ToolDurabilityPolicy {

    private final ToolRiskPolicy riskPolicy;

    ToolDurabilityPolicy() {
        this(new ToolRiskPolicy());
    }

    ToolDurabilityPolicy(ToolRiskPolicy riskPolicy) {
        this.riskPolicy = riskPolicy;
    }

    record DurabilityDecision(boolean required, String reasonCode) {

        static DurabilityDecision none() {
            return new DurabilityDecision(false, null);
        }

        static DurabilityDecision required(String reasonCode) {
            return new DurabilityDecision(true, reasonCode);
        }
    }

    DurabilityDecision evaluate(ToolMetadata metadata, ExecutionEnvelope envelope) {
        if (metadata == null) {
            return DurabilityDecision.none();
        }
        ExecutionEnvelope effectiveEnvelope = envelope != null ? envelope : ExecutionEnvelope.answerOnly();
        if (riskPolicy.evaluate(metadata).externalSideEffect()) {
            return DurabilityDecision.required("external_side_effect_requires_durable_workflow");
        }
        if (metadata.getDurabilityRequirement() == DurabilityRequirement.REQUIRED
                || effectiveEnvelope.durabilityPreference() == DurabilityPreference.REQUIRED) {
            return DurabilityDecision.required("durability_required");
        }
        return DurabilityDecision.none();
    }
}
