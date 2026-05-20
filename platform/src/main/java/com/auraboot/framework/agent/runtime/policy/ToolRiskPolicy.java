package com.auraboot.framework.agent.runtime.policy;

import java.util.Locale;

final class ToolRiskPolicy {

    record RiskDecision(String normalizedRiskLevel, boolean highRisk, boolean externalSideEffect) {
    }

    RiskDecision evaluate(ToolMetadata metadata) {
        if (metadata == null) {
            return new RiskDecision("L0", false, false);
        }
        String normalizedRiskLevel = normalizeRiskLevel(metadata.getRiskLevel());
        boolean highRisk = "L3".equals(normalizedRiskLevel) || "L4".equals(normalizedRiskLevel);
        boolean externalSideEffect = metadata.isExternalSideEffect()
                || metadata.getEffectType() == ToolEffectType.EXTERNAL_ACTION;
        return new RiskDecision(normalizedRiskLevel, highRisk, externalSideEffect);
    }

    private String normalizeRiskLevel(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return "L0";
        }
        return riskLevel.trim().toUpperCase(Locale.ROOT);
    }
}
