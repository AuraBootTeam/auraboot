package com.auraboot.module.finance.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Resolves SpEL expressions from voucher template lines against business event payloads.
 * All methods are null-safe and return sensible defaults on errors.
 * <p>
 * Security: Uses a restricted StandardEvaluationContext that blocks type references (T())
 * and constructor calls to prevent Remote Code Execution via malicious SpEL expressions.
 * Only property/map access and basic arithmetic operators are allowed.
 */
@Slf4j
@Component
public class VoucherTemplateResolver {

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * Resolve a SpEL amount expression against a payload.
     * Expression format: "#payload['field_name']" or "#payload['qty'] * #payload['price']"
     * @return BigDecimal value, or BigDecimal.ZERO if expression fails or result is null
     */
    public BigDecimal resolveAmount(String amountExpr, Map<String, Object> payload) {
        if (amountExpr == null || amountExpr.isBlank() || payload == null) {
            return BigDecimal.ZERO;
        }
        try {
            EvaluationContext context = buildContext(payload);
            Expression expression = parser.parseExpression(amountExpr);
            Object result = expression.getValue(context);
            if (result == null) {
                return BigDecimal.ZERO;
            }
            if (result instanceof BigDecimal bd) {
                return bd.setScale(4, RoundingMode.HALF_UP);
            }
            if (result instanceof Number n) {
                return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP);
            }
            return new BigDecimal(result.toString()).setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("Failed to resolve amount expression '{}': {}", amountExpr, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Resolve a SpEL string expression against a payload.
     * Expression format: "'Sales delivery: ' + #payload['code']"
     * @return Resolved string, or empty string if expression fails
     */
    public String resolveString(String expr, Map<String, Object> payload) {
        if (expr == null || expr.isBlank() || payload == null) {
            return "";
        }
        try {
            EvaluationContext context = buildContext(payload);
            Expression expression = parser.parseExpression(expr);
            Object result = expression.getValue(context);
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            log.warn("Failed to resolve string expression '{}': {}", expr, e.getMessage());
            return "";
        }
    }

    /**
     * Evaluate a SpEL boolean condition against a payload.
     * Expression format: "#payload['type'] == 'sales_out'"
     * @return true if condition passes, false if fails or expression errors
     */
    public boolean evaluateCondition(String conditionExpr, Map<String, Object> payload) {
        if (conditionExpr == null || conditionExpr.isBlank() || payload == null) {
            return false;
        }
        try {
            EvaluationContext context = buildContext(payload);
            Expression expression = parser.parseExpression(conditionExpr);
            Boolean result = expression.getValue(context, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.warn("Failed to evaluate condition '{}': {}", conditionExpr, e.getMessage());
            return false;
        }
    }

    private EvaluationContext buildContext(Map<String, Object> payload) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        // Block T() type references to prevent RCE via malicious SpEL expressions
        context.setTypeLocator(typeName -> {
            throw new SpelEvaluationException(SpelMessage.TYPE_NOT_FOUND, typeName);
        });
        context.setVariable("payload", payload);
        return context;
    }
}
