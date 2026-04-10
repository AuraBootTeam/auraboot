package com.auraboot.framework.meta.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Encapsulates SpEL expression evaluation for the command execution pipeline.
 * Provides a sandboxed evaluation context (no arbitrary method invocation)
 * and utility methods for condition/guard evaluation.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Component
public class CommandSpelEvaluator {

    private final ExpressionParser spelParser = new SpelExpressionParser();

    /**
     * Build a sandboxed SpEL evaluation context from a payload map.
     * Uses SimpleEvaluationContext to prevent arbitrary method invocation (RCE risk).
     * Only allows property reads on Maps — no T() operator, no method calls.
     */
    public EvaluationContext buildSpelContext(Map<String, Object> payload) {
        SimpleEvaluationContext context = SimpleEvaluationContext
                .forPropertyAccessors(new org.springframework.context.expression.MapAccessor())
                .withRootObject(payload)
                .build();
        context.setVariable("payload", payload);
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            context.setVariable(entry.getKey(), entry.getValue());
        }
        return context;
    }

    /**
     * Evaluate a SpEL expression and return the result as the specified type.
     *
     * @param expression the SpEL expression string
     * @param context    the evaluation context
     * @param resultType the expected result type
     * @return the evaluation result, or null on failure
     */
    public <T> T evaluate(String expression, EvaluationContext context, Class<T> resultType) {
        return spelParser.parseExpression(expression).getValue(context, resultType);
    }

    /**
     * Evaluate a SpEL expression and return the raw result.
     */
    public Object evaluate(String expression, EvaluationContext context) {
        return spelParser.parseExpression(expression).getValue(context);
    }

    /**
     * Resolve target state from multi-branch stateTransitionRules.
     * Evaluates conditions as SpEL expressions against the payload.
     *
     * @param rules   list of rule maps, each with "guard"/"condition" and "toState"
     * @param payload the command payload for SpEL evaluation
     * @return the resolved target state, or null if no rule matches
     */
    String resolveMultiBranchTargetState(List<Map<String, Object>> rules, Map<String, Object> payload) {
        EvaluationContext context = buildSpelContext(payload);
        for (Map<String, Object> rule : rules) {
            String condition = (String) rule.get("guard");
            if (condition == null) condition = (String) rule.get("condition"); // legacy fallback
            String toState = (String) rule.get("toState");
            if (condition == null || toState == null) continue;

            try {
                Boolean result = spelParser.parseExpression(condition).getValue(context, Boolean.class);
                if (result != null && result) {
                    return toState;
                }
            } catch (Exception e) {
                log.warn("Failed to evaluate stateTransitionRule condition '{}': {}", condition, e.getMessage());
            }
        }
        return null;
    }
}
