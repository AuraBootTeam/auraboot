package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.runtime.policy.RiskScale;
import org.springframework.stereotype.Component;

/**
 * D1 Grounding: Risk Evaluator — intent + scope → risk level.
 * Pure rules, no LLM call.
 */
@Component
public class RiskEvaluator {

    public String evaluate(String intent, int affectedCount) {
        // Base risk from intent
        RiskScale baseRisk = switch (intent) {
            case "query", "analyze", "summarize", "compare", "explain",
                 "export", "report", "recommend" -> RiskScale.L0;
            case "create", "update", "assign", "notify", "transition" -> RiskScale.L1;
            case "automate" -> RiskScale.L2;
            case "delete" -> RiskScale.L4;
            default -> RiskScale.L1;
        };

        // Elevate for batch operations
        if (affectedCount > 10) baseRisk = RiskScale.max(baseRisk, RiskScale.L2);
        if (affectedCount > 100) baseRisk = RiskScale.max(baseRisk, RiskScale.L3);

        return baseRisk.code();
    }

    public String deriveActionability(String intent) {
        return switch (intent) {
            case "query", "analyze", "summarize", "compare", "explain",
                 "export", "report", "recommend" -> "read_only";
            case "create", "update", "transition", "assign", "notify" -> "execute";
            case "delete", "automate" -> "propose";
            default -> "read_only";
        };
    }

    /**
     * Derive risk level from ab_command_definition.execution_config.type.
     * Used by BIF layer when ObjectResolver resolves a commandCode.
     */
    public String deriveFromCommandType(String executionConfigType) {
        RiskScale risk = switch (executionConfigType) {
            case "create", "update", "state_transition" -> RiskScale.L1;
            case "automate" -> RiskScale.L2;
            case "delete" -> RiskScale.L4;
            default -> RiskScale.L1;
        };
        return risk.code();
    }
}
