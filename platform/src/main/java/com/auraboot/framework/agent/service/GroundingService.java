package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ACP Kernel: GroundingService (D1 Semantic Layer).
 *
 * Compiles natural language into BusinessIntentFrame (BIF) — the IR of the ACP pipeline.
 * Assembles: IntentParser + ObjectResolver + RiskEvaluator + SkillAutoGenerator(candidateSkills).
 *
 * Execution semantics: "BIF is the IR. LLM never directly operates tools,
 * only through this structured semantic representation."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroundingService {

    private final IntentParser intentParser;
    private final ObjectResolver objectResolver;
    private final RiskEvaluator riskEvaluator;
    private final AgentSkillService skillService;
    private final SemanticValidator semanticValidator;
    private final SemanticTermResolver semanticTermResolver;
    private final CapabilityRouter capabilityRouter;
    /** Optional — present when Active Memory is deployed (memory-lifecycle.md §4). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActiveMemoryService activeMemoryService;

    /**
     * Ground a user message into a BusinessIntentFrame.
     *
     * @param tenantId    current tenant
     * @param userMessage user's natural language input
     * @param context     grounding context (pageModel, recordId, etc.)
     * @return BIF with intent, object, confidence, candidateSkills, riskLevel
     */
    public BusinessIntentFrame ground(Long tenantId, String userMessage, GroundingContext context) {
        long startMs = System.currentTimeMillis();

        // 1. Parse intent
        IntentParser.IntentResult intentResult = intentParser.parse(userMessage);

        // 2. Resolve object
        ObjectResolver.ObjectResult objectResult = objectResolver.resolve(tenantId, userMessage);

        // 3. Context fallback: if object not found, try pageModel
        if (objectResult.getModelCode() == null && context != null && context.getPageModel() != null) {
            objectResult = new ObjectResolver.ObjectResult(context.getPageModel(), 0.60, "context", List.of());
        }

        // 4. Compute multi-dimensional confidence
        ConfidenceScore confidence = ConfidenceScore.of(
                intentResult.getConfidence(), objectResult.getConfidence());

        // 5. Evaluate risk
        String riskLevel = riskEvaluator.evaluate(intentResult.getIntent(), 1);
        String actionability = riskEvaluator.deriveActionability(intentResult.getIntent());

        // 5.5 Semantic validation (3-layer legality check)
        SemanticValidator.ValidationResult validation = semanticValidator.validate(
                intentResult.getIntent(), objectResult.getModelCode(), Map.of(), tenantId);
        if (!validation.isValid()) {
            // Downgrade confidence
            confidence = ConfidenceScore.of(
                    intentResult.getConfidence() * validation.getAdjustedConfidence(),
                    objectResult.getConfidence());
        }
        if (validation.getAdjustedActionability() != null) {
            actionability = validation.getAdjustedActionability();
        }

        // 5.6 Semantic term resolution (populate filters and scope from resolved terms)
        List<SemanticTermResolver.ResolvedTerm> resolvedTerms = semanticTermResolver.resolve(
                tenantId, userMessage, objectResult.getModelCode());
        List<Map<String, Object>> filters = new ArrayList<>();
        Map<String, Object> scope = new HashMap<>();
        for (SemanticTermResolver.ResolvedTerm rt : resolvedTerms) {
            if ("filter".equals(rt.getTermType())) {
                Object conditions = rt.getResolution().get("conditions");
                if (conditions instanceof List<?> list) {
                    for (Object c : list) {
                        if (c instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> filterMap = (Map<String, Object>) m;
                            filters.add(filterMap);
                        }
                    }
                }
            } else if ("time_range".equals(rt.getTermType())) {
                scope.put("timeRange", rt.getResolution().get("range"));
            }
        }

        // 6. Resolve candidate skills
        List<String> candidateSkills = resolveCandidateSkills(tenantId, objectResult.getModelCode(), intentResult.getIntent());

        // 7. Determine skills mode
        String skillsMode = resolveSkillsMode(intentResult.getIntent());

        // 8. Build explanation
        Map<String, String> explanation = new LinkedHashMap<>();
        explanation.put("intentMatch", intentResult.getIntent() + " ← " + intentResult.getMatchType()
                + " (confidence=" + String.format("%.2f", intentResult.getConfidence()) + ")");
        explanation.put("objectMatch", (objectResult.getModelCode() != null ? objectResult.getModelCode() : "none")
                + " ← " + objectResult.getMatchType()
                + " (confidence=" + String.format("%.2f", objectResult.getConfidence()) + ")");
        explanation.put("riskReason", riskLevel + ": " + intentResult.getIntent() + " operation");

        // 8.5. Active Memory pre-recall (memory-lifecycle.md §4) — best-effort.
        List<Map<String, Object>> preContext = List.of();
        if (activeMemoryService != null) {
            try {
                String userId = context != null ? context.getUserId() : null;
                String agentCode = context != null ? context.getAgentCode() : null;
                preContext = activeMemoryService.preRecall(tenantId, userId, agentCode, userMessage);
            } catch (Exception e) {
                log.debug("Active Memory pre-recall failed, continuing without preContext: {}", e.getMessage());
            }
        }

        // 9. Build BIF
        BusinessIntentFrame bif = BusinessIntentFrame.builder()
                .intent(intentResult.getIntent())
                .object(objectResult.getModelCode())
                .confidence(confidence)
                .matchType(objectResult.getMatchType())
                .riskLevel(riskLevel)
                .actionability(actionability)
                .candidateSkills(candidateSkills)
                .candidateSkillsMode(skillsMode)
                .filters(filters)
                .semanticConstraints(List.of())
                .explanation(explanation)
                .context(context != null ? Map.of(
                        "pageModel", context.getPageModel() != null ? context.getPageModel() : "",
                        "recordId", context.getRecordId() != null ? context.getRecordId() : ""
                ) : Map.of())
                .preContext(preContext)
                .build();

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("D1 Grounding: intent={}, object={}, confidence={}, skills={}, preContext={}, duration={}ms",
                bif.getIntent(), bif.getObject(), String.format("%.2f", confidence.getOverall()),
                candidateSkills.size(), preContext.size(), durationMs);

        return bif;
    }

    /**
     * Quality gate: check if BIF is reliable enough to drive execution.
     * Returns null if quality is acceptable, or a reason string if fallback is needed.
     */
    public String checkQualityGate(BusinessIntentFrame bif) {
        if (bif.getConfidence().getOverall() < 0.50) {
            return "low_confidence: " + String.format("%.2f", bif.getConfidence().getOverall());
        }
        if (bif.getObject() == null) {
            return "no_object_resolved";
        }
        if (bif.getCandidateSkills() == null || bif.getCandidateSkills().isEmpty()) {
            return "no_candidate_skills";
        }
        if (Math.abs(bif.getConfidence().getIntent() - bif.getConfidence().getObject()) > 0.5) {
            return "confidence_divergence";
        }
        return null; // quality OK
    }

    // ========== Helpers ==========

    private List<String> resolveCandidateSkills(Long tenantId, String objectCode, String intent) {
        if (objectCode == null) return List.of();

        // Phase 1: Try Capability routing (domain-aware)
        List<String> capabilitySkills = capabilityRouter.route(tenantId, intent, objectCode);
        if (!capabilitySkills.isEmpty()) {
            return capabilitySkills;
        }

        // Phase 2: Route to built-in generic Skills (dsl.command / dsl.query)
        if (isReadIntent(intent)) {
            return List.of("dsl.query");
        } else {
            return List.of("dsl.command");
        }
    }

    private boolean isReadIntent(String intent) {
        return Set.of("query", "analyze", "summarize", "compare", "explain", "export", "report", "recommend")
                .contains(intent);
    }

    private String resolveSkillsMode(String intent) {
        return switch (intent) {
            case "query", "analyze", "summarize", "compare", "explain",
                 "export", "report", "recommend" -> "hint";
            case "create", "update", "transition", "assign", "notify" -> "bounded";
            case "delete", "automate" -> "fixed";
            default -> "hint";
        };
    }

    @Data
    @Builder
    @AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class GroundingContext {
        private String pageModel;
        private String recordId;
        private String conversationId;
        private String sessionId;
        /** User id (stringified) — required for Active Memory user-scoped recall. */
        private String userId;
        /**
         * Agent code that owns memories in {@code ab_agent_memory}. Defaults to
         * "aurabot" when null so the built-in chat path keeps working. ACP
         * chat routes MUST set this to their own agent_code or they will
         * inadvertently read aurabot's memories.
         */
        private String agentCode;
    }
}
