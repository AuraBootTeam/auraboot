package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.RuleAssert;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Evaluates the 'assert' clause of a cross-field validation rule.
 * Supports declarative mode (field + operators) and expression mode (expr).
 *
 * Null semantics:
 * - required: true + null → FAIL
 * - Other operators + null field → SKIP (treated as passed)
 * - Ref field is null → SKIP
 */
public final class AssertEvaluator {

    private AssertEvaluator() {}

    public static AssertResult evaluate(RuleAssert assertion, Map<String, Object> data) {
        // Expression mode — delegate to SpEL externally
        if (assertion.getExpr() != null) {
            throw new UnsupportedOperationException("Expression asserts must be evaluated externally via SpEL");
        }

        String field = assertion.getField();
        if (field == null) return AssertResult.PASSED;

        Object value = data.get(field);

        // Required check — null/empty means FAIL
        if (Boolean.TRUE.equals(assertion.getRequired())) {
            if (value == null || (value instanceof String s && s.isBlank())) {
                return AssertResult.failed("required");
            }
        }

        // For non-required operators: null field → SKIP
        if (value == null) {
            return AssertResult.SKIPPED;
        }

        // String constraints
        if (assertion.getMaxLength() != null && value instanceof String s) {
            if (s.length() > assertion.getMaxLength()) return AssertResult.failed("maxLength");
        }
        if (assertion.getMinLength() != null && value instanceof String s) {
            if (s.length() < assertion.getMinLength()) return AssertResult.failed("minLength");
        }
        if (assertion.getPattern() != null && value instanceof String s) {
            if (!Pattern.matches(assertion.getPattern(), s)) return AssertResult.failed("pattern");
        }

        // Comparison operators — ref resolution may return null → SKIP
        if (assertion.getEq() != null) {
            Object rhs = ConditionEvaluator.resolveValue(assertion.getEq(), data);
            if (rhs == null) return AssertResult.SKIPPED;
            if (!ConditionEvaluator.compareOp(value, rhs, "eq")) return AssertResult.failed("eq");
        }
        if (assertion.getNeq() != null) {
            Object rhs = ConditionEvaluator.resolveValue(assertion.getNeq(), data);
            if (rhs == null) return AssertResult.SKIPPED;
            if (!ConditionEvaluator.compareOp(value, rhs, "neq")) return AssertResult.failed("neq");
        }
        if (assertion.getGt() != null) {
            Object rhs = ConditionEvaluator.resolveValue(assertion.getGt(), data);
            if (rhs == null) return AssertResult.SKIPPED;
            if (!ConditionEvaluator.compareOp(value, rhs, "gt")) return AssertResult.failed("gt");
        }
        if (assertion.getGte() != null) {
            Object rhs = ConditionEvaluator.resolveValue(assertion.getGte(), data);
            if (rhs == null) return AssertResult.SKIPPED;
            if (!ConditionEvaluator.compareOp(value, rhs, "gte")) return AssertResult.failed("gte");
        }
        if (assertion.getLt() != null) {
            Object rhs = ConditionEvaluator.resolveValue(assertion.getLt(), data);
            if (rhs == null) return AssertResult.SKIPPED;
            if (!ConditionEvaluator.compareOp(value, rhs, "lt")) return AssertResult.failed("lt");
        }
        if (assertion.getLte() != null) {
            Object rhs = ConditionEvaluator.resolveValue(assertion.getLte(), data);
            if (rhs == null) return AssertResult.SKIPPED;
            if (!ConditionEvaluator.compareOp(value, rhs, "lte")) return AssertResult.failed("lte");
        }

        // In / NotIn
        if (assertion.getIn() != null) {
            boolean found = assertion.getIn().stream().anyMatch(item -> {
                if (value instanceof Number && item instanceof Number) {
                    return ((Number) value).doubleValue() == ((Number) item).doubleValue();
                }
                return value.equals(item);
            });
            if (!found) return AssertResult.failed("in");
        }
        if (assertion.getNotIn() != null) {
            boolean found = assertion.getNotIn().stream().anyMatch(item -> {
                if (value instanceof Number && item instanceof Number) {
                    return ((Number) value).doubleValue() == ((Number) item).doubleValue();
                }
                return value.equals(item);
            });
            if (found) return AssertResult.failed("notIn");
        }

        return AssertResult.PASSED;
    }

    /**
     * Result of assert evaluation.
     */
    public record AssertResult(boolean passed, boolean skipped, String failedOperator) {
        public static final AssertResult PASSED = new AssertResult(true, false, null);
        public static final AssertResult SKIPPED = new AssertResult(true, true, null);

        public static AssertResult failed(String operator) {
            return new AssertResult(false, false, operator);
        }
    }
}
