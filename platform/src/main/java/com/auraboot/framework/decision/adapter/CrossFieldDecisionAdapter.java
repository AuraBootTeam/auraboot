package com.auraboot.framework.decision.adapter;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.DecisionEvaluateOptions;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.ResultType;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.service.impl.CommandSpelEvaluator;
import com.auraboot.framework.meta.validation.CrossFieldRuleEngine;
import com.auraboot.framework.meta.validation.RuleEvaluationResult;
import com.auraboot.framework.meta.validation.RuleViolation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.expression.EvaluationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter that brings the existing {@link CrossFieldRuleEngine} under the unified Decision Runtime
 * (docs/1.md §16.3): a CROSS_FIELD decision's content is a set of cross-field rules; the engine's
 * {@code errors}/{@code warnings} become {@link DecisionResult.Violation}s. Declarative rules
 * (when/assert) are supported here; expression-mode rules need a SpEL evaluator wired (future).
 */
public class CrossFieldDecisionAdapter implements DecisionAdapter {

    private final ObjectMapper mapper;
    private final CommandSpelEvaluator spelEvaluator;

    public CrossFieldDecisionAdapter(ObjectMapper mapper, CommandSpelEvaluator spelEvaluator) {
        this.mapper = mapper;
        this.spelEvaluator = spelEvaluator;
    }

    public CrossFieldDecisionAdapter() {
        this(new ObjectMapper(), new CommandSpelEvaluator());
    }

    @Override
    public boolean supports(ResolvedDecision decision) {
        return decision.kind() == DecisionKind.CROSS_FIELD
                && (decision.runtimeAdapter() == null || decision.runtimeAdapter() == RuntimeAdapter.CROSS_FIELD_ENGINE);
    }

    @Override
    public DecisionValidateResult validate(ResolvedDecision decision) {
        List<CrossFieldRule> rules;
        try {
            rules = parseRules(decision.content());
        } catch (Exception e) {
            return DecisionValidateResult.invalid(List.of(
                    new DecisionValidateResult.Issue("CROSS_FIELD_PARSE_ERROR", e.getMessage())));
        }
        List<DecisionValidateResult.Issue> errors = new ArrayList<>();
        if (rules.isEmpty()) {
            errors.add(new DecisionValidateResult.Issue("CROSS_FIELD_STRUCTURE", "no cross-field rules"));
        }
        Set<String> fieldRefs = new LinkedHashSet<>();
        for (CrossFieldRule r : rules) {
            if (r.getId() == null || r.getId().isBlank()) {
                errors.add(new DecisionValidateResult.Issue("CROSS_FIELD_STRUCTURE", "rule missing id"));
            }
            if (r.getRuleAssert() == null && r.getWhen() == null) {
                errors.add(new DecisionValidateResult.Issue("CROSS_FIELD_STRUCTURE",
                        "rule " + r.getId() + " has neither when nor assert"));
            }
            if (r.getTargetField() != null) {
                fieldRefs.add("record.data." + r.getTargetField());
            }
        }
        if (!errors.isEmpty()) {
            return DecisionValidateResult.invalid(errors);
        }
        return DecisionValidateResult.ok(new ArrayList<>(fieldRefs), List.of());
    }

    @Override
    public DecisionResult evaluate(ResolvedDecision decision, DecisionContext context, DecisionEvaluateOptions options) {
        List<CrossFieldRule> rules = parseRules(decision.content());
        Map<String, Object> recordData = recordData(context);

        // Build a per-evaluation engine whose SpEL evaluator (for expression-mode rules) reuses the
        // platform's safe CommandSpelEvaluator (SimpleEvaluationContext — no bean/type/method refs),
        // mirroring PreInvariantPhase. Declarative when/assert rules need no SpEL.
        EvaluationContext spelContext = spelEvaluator.buildSpelContext(recordData);
        CrossFieldRuleEngine engine = new CrossFieldRuleEngine(
                expr -> Boolean.TRUE.equals(spelEvaluator.evaluate(expr, spelContext, Boolean.class)));
        RuleEvaluationResult result = engine.evaluate(rules, List.of(), recordData);

        List<DecisionResult.Violation> violations = new ArrayList<>();
        result.errors().forEach(v -> violations.add(toViolation(v, "ERROR")));
        result.warnings().forEach(v -> violations.add(toViolation(v, "WARNING")));

        boolean hasErrors = result.hasErrors();
        return DecisionResult.builder(decision.decisionCode())
                .version(decision.version())
                .kind(DecisionKind.CROSS_FIELD)
                .engineType(RuntimeAdapter.CROSS_FIELD_ENGINE)
                .resultType(ResultType.VALIDATION)
                .status(hasErrors ? DecisionStatus.VIOLATED : DecisionStatus.NOT_MATCHED)
                .matched(false)
                .violations(violations)
                .build();
    }

    private DecisionResult.Violation toViolation(RuleViolation v, String fallbackSeverity) {
        String severity = v.severity() != null ? v.severity() : fallbackSeverity;
        String fieldPath = v.targetField() != null ? "record.data." + v.targetField() : null;
        return new DecisionResult.Violation(fieldPath, v.ruleId(), v.message(), severity);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recordData(DecisionContext context) {
        DecisionContext.PathValue pv = context.resolve(Scope.RECORD, "data");
        if (pv.present() && pv.value() instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private List<CrossFieldRule> parseRules(JsonNode content) {
        JsonNode rulesNode = content != null && content.has("rules") ? content.get("rules") : content;
        if (rulesNode == null || rulesNode.isNull()) {
            return List.of();
        }
        try {
            return mapper.convertValue(rulesNode, new TypeReference<List<CrossFieldRule>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cross-field rules content: " + e.getMessage(), e);
        }
    }
}
