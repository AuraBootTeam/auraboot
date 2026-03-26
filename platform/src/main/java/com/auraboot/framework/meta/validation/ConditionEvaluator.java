package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.RuleCondition;

import java.util.List;
import java.util.Map;

/**
 * Evaluates declarative conditions (when/assert).
 * Supports single conditions, compound AND/OR/NOT, and ref comparisons.
 * Expression mode (expr) throws UnsupportedOperationException — handled by caller.
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {}

    public static boolean evaluate(RuleCondition condition, Map<String, Object> data) {
        // Compound conditions
        if (condition.getAnd() != null) {
            return condition.getAnd().stream().allMatch(c -> evaluate(c, data));
        }
        if (condition.getOr() != null) {
            return condition.getOr().stream().anyMatch(c -> evaluate(c, data));
        }
        if (condition.getNot() != null) {
            return !evaluate(condition.getNot(), data);
        }

        // Expression mode — delegate to SpEL externally
        if (condition.getExpr() != null) {
            throw new UnsupportedOperationException("Expression conditions must be evaluated externally via SpEL");
        }

        // Single declarative condition
        return evaluateSingle(condition, data);
    }

    private static boolean evaluateSingle(RuleCondition cond, Map<String, Object> data) {
        String fieldName = cond.getField();
        if (fieldName == null) return true; // no field = vacuously true

        Object fieldValue = data.get(fieldName);
        if (fieldValue == null) return false; // null field → condition is false

        // Check each operator that is set
        if (cond.getEq() != null && !compareOp(fieldValue, resolveValue(cond.getEq(), data), "eq")) return false;
        if (cond.getNeq() != null && !compareOp(fieldValue, resolveValue(cond.getNeq(), data), "neq")) return false;
        if (cond.getGt() != null && !compareOp(fieldValue, resolveValue(cond.getGt(), data), "gt")) return false;
        if (cond.getGte() != null && !compareOp(fieldValue, resolveValue(cond.getGte(), data), "gte")) return false;
        if (cond.getLt() != null && !compareOp(fieldValue, resolveValue(cond.getLt(), data), "lt")) return false;
        if (cond.getLte() != null && !compareOp(fieldValue, resolveValue(cond.getLte(), data), "lte")) return false;
        if (cond.getIn() != null && !evalIn(fieldValue, cond.getIn())) return false;
        if (cond.getNotIn() != null && evalIn(fieldValue, cond.getNotIn())) return false;

        return true;
    }

    static Object resolveValue(Object value, Map<String, Object> data) {
        if (value instanceof Map<?, ?> map && map.containsKey("ref")) {
            return data.get(map.get("ref").toString());
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static boolean compareOp(Object left, Object right, String op) {
        if (right == null) return false;

        // Normalize numeric types for comparison
        if (left instanceof Number && right instanceof Number) {
            double l = ((Number) left).doubleValue();
            double r = ((Number) right).doubleValue();
            return switch (op) {
                case "eq" -> l == r;
                case "neq" -> l != r;
                case "gt" -> l > r;
                case "gte" -> l >= r;
                case "lt" -> l < r;
                case "lte" -> l <= r;
                default -> false;
            };
        }

        // Comparable types (String, Date, etc.)
        if (left instanceof Comparable && right instanceof Comparable) {
            try {
                int cmp = ((Comparable<Object>) left).compareTo(right);
                return switch (op) {
                    case "eq" -> cmp == 0;
                    case "neq" -> cmp != 0;
                    case "gt" -> cmp > 0;
                    case "gte" -> cmp >= 0;
                    case "lt" -> cmp < 0;
                    case "lte" -> cmp <= 0;
                    default -> false;
                };
            } catch (ClassCastException e) {
                // Incompatible types — compare via toString
                return compareOp(left.toString(), right.toString(), op);
            }
        }

        // Fallback: equals only
        return switch (op) {
            case "eq" -> left.equals(right);
            case "neq" -> !left.equals(right);
            default -> false;
        };
    }

    private static boolean evalIn(Object value, List<Object> list) {
        // Handle numeric type coercion
        if (value instanceof Number) {
            double v = ((Number) value).doubleValue();
            return list.stream().anyMatch(item ->
                item instanceof Number && ((Number) item).doubleValue() == v);
        }
        return list.contains(value);
    }
}
