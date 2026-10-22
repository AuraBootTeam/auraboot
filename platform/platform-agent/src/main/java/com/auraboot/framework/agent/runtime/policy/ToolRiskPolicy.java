package com.auraboot.framework.agent.runtime.policy;

final class ToolRiskPolicy {

    record RiskDecision(String normalizedRiskLevel, boolean highRisk, boolean externalSideEffect) {
    }

    RiskDecision evaluate(ToolMetadata metadata) {
        if (metadata == null) {
            return new RiskDecision("L0", false, false);
        }
        String normalizedRiskLevel = normalizeRiskLevel(metadata.getRiskLevel());
        boolean highRisk = RiskScale.parse(normalizedRiskLevel).requiresHumanApproval();
        boolean externalSideEffect = metadata.isExternalSideEffect()
                || metadata.getEffectType() == ToolEffectType.EXTERNAL_ACTION;
        return new RiskDecision(normalizedRiskLevel, highRisk, externalSideEffect);
    }

    private String normalizeRiskLevel(String riskLevel) {
        return RiskScale.parseOrDefault(riskLevel, RiskScale.L0).code();
    }
}
