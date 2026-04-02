package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * D1 Grounding: Semantic Validator — 3-layer intent legality checking.
 * Layer 1: Intent x Object (does the model support this operation?)
 * Layer 2: Intent x Scope (is the scope complete for this operation?)
 * Layer 3: Intent x Actionability (is execution mode appropriate for risk?)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticValidator {

    private final DynamicDataMapper dynamicDataMapper;

    public ValidationResult validate(String intent, String modelCode, Map<String, Object> scope, Long tenantId) {
        // Layer 1: Intent x Object
        ValidationResult l1 = validateIntentObject(intent, modelCode, tenantId);
        if (!l1.isValid()) return l1;

        // Layer 2: Intent x Scope
        ValidationResult l2 = validateIntentScope(intent, scope);
        if (!l2.isValid()) return l2;

        // Layer 3: Intent x Actionability
        String adjustedActionability = deriveActionability(intent);

        return ValidationResult.builder()
                .valid(true)
                .adjustedConfidence(Math.min(l1.getAdjustedConfidence(), l2.getAdjustedConfidence()))
                .adjustedActionability(adjustedActionability)
                .build();
    }

    // Layer 1: Check if model supports the intent operation
    private ValidationResult validateIntentObject(String intent, String modelCode, Long tenantId) {
        if (modelCode == null) {
            return ValidationResult.builder().valid(true).adjustedConfidence(1.0).build();
        }

        // For write intents, check if corresponding command exists
        String requiredCommandType = switch (intent) {
            case "create" -> "create";
            case "update", "assign" -> "update";
            case "delete" -> "delete";
            case "transition" -> "state_transition";
            default -> null; // read intents don't need command
        };

        if (requiredCommandType != null) {
            String sql = "SELECT COUNT(*) as cnt FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} AND model_code = #{params.modelCode} " +
                    "AND execution_config->>'type' = #{params.cmdType} AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
            try {
                List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                        Map.of("tenantId", tenantId, "modelCode", modelCode, "cmdType", requiredCommandType));
                long count = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("cnt")).longValue();
                if (count == 0) {
                    return ValidationResult.builder()
                            .valid(false)
                            .adjustedConfidence(0.20)
                            .reason("Model " + modelCode + " has no " + requiredCommandType + " command")
                            .suggestedIntent(requiredCommandType.equals("state_transition") ? null : "query")
                            .build();
                }
            } catch (Exception e) {
                log.debug("Failed to validate intent-object: {}", e.getMessage());
            }
        }

        return ValidationResult.builder().valid(true).adjustedConfidence(1.0).build();
    }

    // Layer 2: Check if scope is complete for the intent
    private ValidationResult validateIntentScope(String intent, Map<String, Object> scope) {
        // Write operations that target specific records need recordId or filters
        if (Set.of("update", "delete", "transition", "assign").contains(intent)) {
            boolean hasRecordId = scope != null && scope.get("recordIds") != null;
            if (!hasRecordId) {
                // Not an error, just reduces confidence (user may clarify)
                return ValidationResult.builder()
                        .valid(true) // still valid, just lower confidence
                        .adjustedConfidence(0.60)
                        .reason("No specific record targeted for " + intent)
                        .build();
            }
        }
        return ValidationResult.builder().valid(true).adjustedConfidence(1.0).build();
    }

    private String deriveActionability(String intent) {
        return switch (intent) {
            case "query", "analyze", "summarize", "compare", "explain",
                 "export", "report", "recommend" -> "read_only";
            case "create", "update", "transition", "assign", "notify" -> "execute";
            case "delete", "automate" -> "propose";
            default -> "read_only";
        };
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean valid;
        @Builder.Default
        private double adjustedConfidence = 1.0;
        private String adjustedActionability;
        private String reason;
        private String suggestedIntent;
    }
}
