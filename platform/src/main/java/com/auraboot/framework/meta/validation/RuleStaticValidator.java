package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleAssert;

import java.util.*;

/**
 * Validates cross-field rules at import time (static analysis).
 * Returns a list of error messages — empty means valid.
 */
public final class RuleStaticValidator {

    private RuleStaticValidator() {}

    public static List<String> validate(List<CrossFieldRule> rules, Set<String> knownFieldCodes) {
        List<String> errors = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (CrossFieldRule rule : rules) {
            // 1. Check id
            if (rule.getId() == null || rule.getId().isBlank()) {
                errors.add("Rule id is required");
                continue;
            }
            if (!seenIds.add(rule.getId())) {
                errors.add("Duplicate rule id: " + rule.getId());
            }

            // 2. Check message
            if (rule.getMessage() == null || rule.getMessage().isBlank()) {
                errors.add("Rule message is required for rule: " + rule.getId());
            }

            // 3. Check assert
            RuleAssert a = rule.getRuleAssert();
            if (a == null) {
                errors.add("Rule assert is required for rule: " + rule.getId());
                continue;
            }

            // 4. Mode detection: field and expr are mutually exclusive
            boolean hasField = a.getField() != null;
            boolean hasExpr = a.getExpr() != null;

            if (hasField && hasExpr) {
                errors.add("Assert cannot have both declarative and expression mode in rule: " + rule.getId());
                continue;
            }

            if (hasField) {
                // 5. Declarative: must have at least one operator
                if (!hasAnyOperator(a)) {
                    errors.add("Assert must have at least one operator in rule: " + rule.getId());
                }

                // 6. Check ref references
                checkRefFields(a, knownFieldCodes, rule.getId(), errors);

                // 7. Check in/notIn for null
                checkInArraysForNull(a, rule.getId(), errors);
            }

            if (hasExpr) {
                // 8. Expression must have dependsOn
                if (rule.getDependsOn() == null || rule.getDependsOn().isEmpty()) {
                    errors.add("Expression rules require explicit dependsOn in rule: " + rule.getId());
                }
            }
        }

        return errors;
    }

    private static boolean hasAnyOperator(RuleAssert a) {
        return a.getEq() != null || a.getNeq() != null
            || a.getGt() != null || a.getGte() != null
            || a.getLt() != null || a.getLte() != null
            || a.getIn() != null || a.getNotIn() != null
            || Boolean.TRUE.equals(a.getRequired())
            || a.getMaxLength() != null || a.getMinLength() != null
            || a.getPattern() != null;
    }

    private static void checkRefFields(RuleAssert a, Set<String> knownFields, String ruleId, List<String> errors) {
        checkSingleRef(a.getEq(), knownFields, ruleId, errors);
        checkSingleRef(a.getNeq(), knownFields, ruleId, errors);
        checkSingleRef(a.getGt(), knownFields, ruleId, errors);
        checkSingleRef(a.getGte(), knownFields, ruleId, errors);
        checkSingleRef(a.getLt(), knownFields, ruleId, errors);
        checkSingleRef(a.getLte(), knownFields, ruleId, errors);
    }

    private static void checkSingleRef(Object value, Set<String> knownFields, String ruleId, List<String> errors) {
        if (value instanceof Map<?, ?> map && map.containsKey("ref")) {
            String refField = map.get("ref").toString();
            if (!knownFields.contains(refField)) {
                errors.add("Rule references unknown field: " + refField + " in rule: " + ruleId);
            }
        }
    }

    private static void checkInArraysForNull(RuleAssert a, String ruleId, List<String> errors) {
        if (a.getIn() != null && a.getIn().stream().anyMatch(Objects::isNull)) {
            errors.add("in/notIn arrays must not contain null in rule: " + ruleId);
        }
        if (a.getNotIn() != null && a.getNotIn().stream().anyMatch(Objects::isNull)) {
            errors.add("in/notIn arrays must not contain null in rule: " + ruleId);
        }
    }
}
