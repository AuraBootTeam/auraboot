package com.auraboot.framework.permission.engine.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Lightweight expression evaluator for policy rules.
 *
 * <p>Supports operator-based rule evaluation as defined in policy_schema:
 * <pre>
 * {
 *   "maxApprovalAmount": {
 *     "type": "number",
 *     "operator": "<=",
 *     "field": "amount"
 *   }
 * }
 * </pre>
 *
 * <p>Supported operators: {@code <=}, {@code >=}, {@code <}, {@code >},
 * {@code ==}, {@code !=}, {@code in}, {@code not_in}.
 *
 * <p>Null record values pass policy checks (lenient).
 * Numbers are compared as doubles; strings are compared as strings.
 */
@Slf4j
@Component
public class PolicyExpressionEvaluator {

    /**
     * Evaluate a policy rule against a record.
     *
     * @param ruleKey     the policy rule key (e.g. "maxApprovalAmount")
     * @param rule        policy rule definition from policy_schema: { type, operator, field }
     * @param policyValue the configured limit/value from ab_role_permission.conditions
     * @param record      the data record being checked
     * @return PolicyViolation if violated, null if satisfied
     */
    public PolicyViolation evaluate(String ruleKey, Map<String, Object> rule,
                                     Object policyValue, Map<?, ?> record) {
        String field = (String) rule.get("field");
        String operator = (String) rule.get("operator");

        if (field == null || operator == null) {
            // Legacy format (no operator/field) -- fall back to convention-based matching
            return evaluateLegacy(ruleKey, policyValue, record);
        }

        Object recordValue = record.get(field);
        if (recordValue == null) {
            return null; // null values pass policy checks
        }

        return evaluateOperator(ruleKey, operator, recordValue, policyValue, field);
    }

    // ========================================================================
    // Operator evaluation
    // ========================================================================

    private PolicyViolation evaluateOperator(String ruleKey, String operator,
                                              Object recordValue, Object policyValue,
                                              String field) {
        return switch (operator) {
            case "<=" -> evaluateComparison(ruleKey, recordValue, policyValue, field, operator);
            case ">=" -> evaluateComparison(ruleKey, recordValue, policyValue, field, operator);
            case "<" -> evaluateComparison(ruleKey, recordValue, policyValue, field, operator);
            case ">" -> evaluateComparison(ruleKey, recordValue, policyValue, field, operator);
            case "==" -> evaluateEquality(ruleKey, recordValue, policyValue, field, false);
            case "!=" -> evaluateEquality(ruleKey, recordValue, policyValue, field, true);
            case "in" -> evaluateInList(ruleKey, recordValue, policyValue, field, false);
            case "not_in" -> evaluateInList(ruleKey, recordValue, policyValue, field, true);
            default -> {
                log.warn("Unknown policy operator '{}' for rule '{}'", operator, ruleKey);
                yield null;
            }
        };
    }

    private PolicyViolation evaluateComparison(String ruleKey, Object recordValue,
                                                Object policyValue, String field,
                                                String operator) {
        Double recordNum = toDouble(recordValue);
        Double limitNum = toDouble(policyValue);

        if (recordNum == null || limitNum == null) {
            log.debug("Non-numeric comparison skipped for rule '{}': record={}, limit={}",
                    ruleKey, recordValue, policyValue);
            return null;
        }

        boolean violated = switch (operator) {
            case "<=" -> recordNum > limitNum;
            case ">=" -> recordNum < limitNum;
            case "<" -> recordNum >= limitNum;
            case ">" -> recordNum <= limitNum;
            default -> false;
        };

        if (violated) {
            return new PolicyViolation(ruleKey,
                    field + " " + recordNum + " violates " + operator + " " + limitNum);
        }
        return null;
    }

    private PolicyViolation evaluateEquality(String ruleKey, Object recordValue,
                                              Object policyValue, String field,
                                              boolean negate) {
        String recordStr = String.valueOf(recordValue);
        String policyStr = String.valueOf(policyValue);
        boolean equal = recordStr.equals(policyStr);

        if (negate) {
            // != operator: violated if values ARE equal
            if (equal) {
                return new PolicyViolation(ruleKey,
                        field + " must not equal " + policyStr);
            }
        } else {
            // == operator: violated if values are NOT equal
            if (!equal) {
                return new PolicyViolation(ruleKey,
                        field + " must equal " + policyStr + " but was " + recordStr);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private PolicyViolation evaluateInList(String ruleKey, Object recordValue,
                                            Object policyValue, String field,
                                            boolean negate) {
        Collection<Object> allowedValues;
        if (policyValue instanceof Collection<?> coll) {
            allowedValues = (Collection<Object>) coll;
        } else if (policyValue instanceof Object[] arr) {
            allowedValues = List.of(arr);
        } else {
            allowedValues = List.of(policyValue);
        }

        String recordStr = String.valueOf(recordValue);
        boolean found = allowedValues.stream()
                .map(String::valueOf)
                .anyMatch(recordStr::equals);

        if (negate) {
            // not_in: violated if value IS in the list
            if (found) {
                return new PolicyViolation(ruleKey,
                        field + " value '" + recordStr + "' is in restricted list");
            }
        } else {
            // in: violated if value is NOT in the list
            if (!found) {
                return new PolicyViolation(ruleKey,
                        field + " value '" + recordStr + "' is not in allowed list " + allowedValues);
            }
        }
        return null;
    }

    // ========================================================================
    // Legacy convention-based evaluation
    // ========================================================================

    /**
     * Legacy evaluation using key naming conventions (maxXxx / minXxx).
     */
    private PolicyViolation evaluateLegacy(String ruleKey, Object policyValue, Map<?, ?> record) {
        if (ruleKey.startsWith("max") && ruleKey.length() > 3 && policyValue instanceof Number maxVal) {
            String fieldKey = ruleKey.substring(3, 4).toLowerCase() + ruleKey.substring(4);
            Object recordVal = record.get(fieldKey);
            if (recordVal instanceof Number numVal && numVal.doubleValue() > maxVal.doubleValue()) {
                return new PolicyViolation(ruleKey,
                        ruleKey + ": " + numVal + " exceeds limit " + maxVal);
            }
        }

        if (ruleKey.startsWith("min") && ruleKey.length() > 3 && policyValue instanceof Number minVal) {
            String fieldKey = ruleKey.substring(3, 4).toLowerCase() + ruleKey.substring(4);
            Object recordVal = record.get(fieldKey);
            if (recordVal instanceof Number numVal && numVal.doubleValue() < minVal.doubleValue()) {
                return new PolicyViolation(ruleKey,
                        ruleKey + ": " + numVal + " below minimum " + minVal);
            }
        }

        return null;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Double toDouble(Object value) {
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        if (value instanceof String str) {
            // CATCH: non-transactional, safe to handle -- number parsing from record value
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
