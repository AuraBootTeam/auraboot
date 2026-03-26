package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleAssert;
import com.auraboot.framework.meta.dto.RuleCondition;
import com.auraboot.framework.meta.dto.RuleOverride;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main orchestrator for cross-field validation.
 * Merges model rules with command overrides, evaluates each rule
 * (when condition → assert), and collects errors/warnings.
 *
 * Supports declarative mode natively. Expression mode is evaluated
 * via a pluggable SpEL evaluator function.
 */
@Slf4j
public class CrossFieldRuleEngine {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(\\w+)}");
    private static final String I18N_PREFIX = "$i18n:";

    private final Function<String, Boolean> spelEvaluator;
    private final Function<String, String> i18nResolver;

    /**
     * @param spelEvaluator function that evaluates a SpEL expression string against
     *                      record data and returns a boolean result. Provided by the
     *                      caller (typically CommandExecutorImpl with its SpEL context).
     *                      Can be null if expression mode is not needed.
     * @param i18nResolver  function that resolves a `$i18n:key` reference to a localized
     *                      string. Provided by the caller with the appropriate locale.
     *                      Can be null — raw key is returned when unresolved.
     */
    public CrossFieldRuleEngine(Function<String, Boolean> spelEvaluator, Function<String, String> i18nResolver) {
        this.spelEvaluator = spelEvaluator;
        this.i18nResolver = i18nResolver;
    }

    /**
     * SpEL support only — no i18n resolution.
     */
    public CrossFieldRuleEngine(Function<String, Boolean> spelEvaluator) {
        this(spelEvaluator, null);
    }

    /**
     * No SpEL support, no i18n resolution.
     */
    public CrossFieldRuleEngine() {
        this(null, null);
    }

    public RuleEvaluationResult evaluate(
            List<CrossFieldRule> modelRules,
            List<RuleOverride> commandOverrides,
            Map<String, Object> recordData) {

        List<CrossFieldRule> finalRules = RuleMerger.merge(
            modelRules != null ? modelRules : List.of(),
            commandOverrides
        );

        List<RuleViolation> errors = new ArrayList<>();
        List<RuleViolation> warnings = new ArrayList<>();

        for (CrossFieldRule rule : finalRules) {
            try {
                evaluateRule(rule, recordData, errors, warnings);
            } catch (Exception e) {
                log.warn("Error evaluating rule '{}': {}", rule.getId(), e.getMessage());
            }
        }

        return new RuleEvaluationResult(errors, warnings);
    }

    private void evaluateRule(CrossFieldRule rule, Map<String, Object> data,
                              List<RuleViolation> errors, List<RuleViolation> warnings) {

        // 1. Evaluate when condition
        if (rule.getWhen() != null) {
            boolean conditionMet = evaluateCondition(rule.getWhen(), data);
            if (!conditionMet) return; // Skip rule
        }

        // 2. Evaluate assert
        RuleAssert assertion = rule.getRuleAssert();
        if (assertion == null) return;

        boolean passed;
        if (assertion.getExpr() != null) {
            // Expression mode
            passed = evaluateExpression(assertion.getExpr());
        } else {
            // Declarative mode
            AssertEvaluator.AssertResult result = AssertEvaluator.evaluate(assertion, data);
            if (result.skipped()) return; // Null field → skip
            passed = result.passed();
        }

        if (!passed) {
            String targetField = resolveTargetField(rule);
            String message = resolveMessage(rule.getMessage(), data);
            var violation = new RuleViolation(rule.getId(), targetField, message, rule.getSeverity());

            if ("warning".equals(rule.getSeverity())) {
                warnings.add(violation);
            } else {
                errors.add(violation);
            }
        }
    }

    private boolean evaluateCondition(RuleCondition when, Map<String, Object> data) {
        if (when.getExpr() != null) {
            return evaluateExpression(when.getExpr());
        }
        return ConditionEvaluator.evaluate(when, data);
    }

    private boolean evaluateExpression(String expr) {
        if (spelEvaluator == null) {
            throw new UnsupportedOperationException(
                "Expression evaluation requires a SpEL evaluator. " +
                "Provide one via constructor.");
        }
        try {
            return Boolean.TRUE.equals(spelEvaluator.apply(expr));
        } catch (Exception e) {
            log.debug("Expression evaluation failed (skipping rule): expr='{}', error={}",
                expr, e.getMessage());
            return true; // Expression error → skip rule (null semantics)
        }
    }

    private String resolveTargetField(CrossFieldRule rule) {
        if (rule.getTargetField() != null) return rule.getTargetField();
        if (rule.getRuleAssert() != null && rule.getRuleAssert().getField() != null) {
            return rule.getRuleAssert().getField();
        }
        return null; // Form-level error
    }

    String resolveMessage(String template, Map<String, Object> data) {
        if (template == null) return "Validation failed";
        // Resolve $i18n: prefix before placeholder substitution
        String resolved = template;
        if (template.startsWith(I18N_PREFIX)) {
            String key = template.substring(I18N_PREFIX.length());
            if (i18nResolver != null) {
                String i18nValue = i18nResolver.apply(key);
                resolved = (i18nValue != null) ? i18nValue : key;
            } else {
                resolved = key; // Strip prefix, return raw key
            }
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(resolved);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String fieldCode = matcher.group(1);
            Object value = data.get(fieldCode);
            matcher.appendReplacement(sb, value != null ? value.toString() : fieldCode);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
