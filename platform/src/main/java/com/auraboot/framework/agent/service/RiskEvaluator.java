package com.auraboot.framework.agent.service;

import org.springframework.stereotype.Component;

/**
 * D1 Grounding: Risk Evaluator — intent + scope → risk level.
 * Pure rules, no LLM call.
 */
@Component
public class RiskEvaluator {

    public String evaluate(String intent, int affectedCount) {
        // Base risk from intent
        String baseRisk = switch (intent) {
            case "query", "analyze", "summarize", "compare", "explain",
                 "export", "report", "recommend" -> "L0";
            case "create", "update", "assign", "notify" -> "L1";
            case "transition" -> "L1";
            case "automate" -> "L2";
            case "delete" -> "L4";
            default -> "L1";
        };

        // Elevate for batch operations
        if (affectedCount > 10) baseRisk = maxRisk(baseRisk, "L2");
        if (affectedCount > 100) baseRisk = maxRisk(baseRisk, "L3");

        return baseRisk;
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
        return switch (executionConfigType) {
            case "create", "update" -> "L1";
            case "state_transition" -> "L1";
            case "automate" -> "L2";
            case "delete" -> "L4";
            default -> "L1";
        };
    }

    private String maxRisk(String a, String b) {
        return a.compareTo(b) > 0 ? a : b;
    }
}
