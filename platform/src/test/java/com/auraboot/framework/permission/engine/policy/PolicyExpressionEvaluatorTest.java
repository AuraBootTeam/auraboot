package com.auraboot.framework.permission.engine.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PolicyExpressionEvaluator}.
 *
 * <p>Covers all 8 operators, legacy convention fallback, type coercion,
 * null handling, and edge cases.
 */
class PolicyExpressionEvaluatorTest {

    private PolicyExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new PolicyExpressionEvaluator();
    }

    // ========================================================================
    // Comparison operators: <=, >=, <, >
    // ========================================================================

    @Nested
    @DisplayName("<= operator")
    class LessThanOrEqual {

        @Test
        void shouldPassWhenRecordValueBelowLimit() {
            Map<String, Object> rule = Map.of("field", "amount", "operator", "<=");
            Map<String, Object> record = Map.of("amount", 50000);
            PolicyViolation result = evaluator.evaluate("maxAmount", rule, 100000, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldPassWhenRecordValueEqualsLimit() {
            Map<String, Object> rule = Map.of("field", "amount", "operator", "<=");
            Map<String, Object> record = Map.of("amount", 100000);
            PolicyViolation result = evaluator.evaluate("maxAmount", rule, 100000, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldViolateWhenRecordValueExceedsLimit() {
            Map<String, Object> rule = Map.of("field", "amount", "operator", "<=");
            Map<String, Object> record = Map.of("amount", 150000);
            PolicyViolation result = evaluator.evaluate("maxAmount", rule, 100000, record);
            assertThat(result).isNotNull();
            assertThat(result.ruleKey()).isEqualTo("maxAmount");
            assertThat(result.message()).contains("amount", "150000", "<=", "100000");
        }
    }

    @Nested
    @DisplayName(">= operator")
    class GreaterThanOrEqual {

        @Test
        void shouldPassWhenRecordValueAboveLimit() {
            Map<String, Object> rule = Map.of("field", "quantity", "operator", ">=");
            Map<String, Object> record = Map.of("quantity", 10);
            PolicyViolation result = evaluator.evaluate("minQuantity", rule, 1, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldPassWhenRecordValueEqualsLimit() {
            Map<String, Object> rule = Map.of("field", "quantity", "operator", ">=");
            Map<String, Object> record = Map.of("quantity", 1);
            PolicyViolation result = evaluator.evaluate("minQuantity", rule, 1, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldViolateWhenRecordValueBelowLimit() {
            Map<String, Object> rule = Map.of("field", "quantity", "operator", ">=");
            Map<String, Object> record = Map.of("quantity", 0);
            PolicyViolation result = evaluator.evaluate("minQuantity", rule, 1, record);
            assertThat(result).isNotNull();
            assertThat(result.ruleKey()).isEqualTo("minQuantity");
        }
    }

    @Nested
    @DisplayName("< operator")
    class LessThan {

        @Test
        void shouldPassWhenStrictlyLess() {
            Map<String, Object> rule = Map.of("field", "discount", "operator", "<");
            Map<String, Object> record = Map.of("discount", 49);
            PolicyViolation result = evaluator.evaluate("maxDiscount", rule, 50, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldViolateWhenEqual() {
            Map<String, Object> rule = Map.of("field", "discount", "operator", "<");
            Map<String, Object> record = Map.of("discount", 50);
            PolicyViolation result = evaluator.evaluate("maxDiscount", rule, 50, record);
            assertThat(result).isNotNull();
        }

        @Test
        void shouldViolateWhenGreater() {
            Map<String, Object> rule = Map.of("field", "discount", "operator", "<");
            Map<String, Object> record = Map.of("discount", 51);
            PolicyViolation result = evaluator.evaluate("maxDiscount", rule, 50, record);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("> operator")
    class GreaterThan {

        @Test
        void shouldPassWhenStrictlyGreater() {
            Map<String, Object> rule = Map.of("field", "priority", "operator", ">");
            Map<String, Object> record = Map.of("priority", 5);
            PolicyViolation result = evaluator.evaluate("minPriority", rule, 3, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldViolateWhenEqual() {
            Map<String, Object> rule = Map.of("field", "priority", "operator", ">");
            Map<String, Object> record = Map.of("priority", 3);
            PolicyViolation result = evaluator.evaluate("minPriority", rule, 3, record);
            assertThat(result).isNotNull();
        }

        @Test
        void shouldViolateWhenLess() {
            Map<String, Object> rule = Map.of("field", "priority", "operator", ">");
            Map<String, Object> record = Map.of("priority", 1);
            PolicyViolation result = evaluator.evaluate("minPriority", rule, 3, record);
            assertThat(result).isNotNull();
        }
    }

    // ========================================================================
    // Equality operators: ==, !=
    // ========================================================================

    @Nested
    @DisplayName("== operator")
    class EqualOperator {

        @Test
        void shouldPassWhenValuesMatch() {
            Map<String, Object> rule = Map.of("field", "status", "operator", "==");
            Map<String, Object> record = Map.of("status", "approved");
            PolicyViolation result = evaluator.evaluate("requiredStatus", rule, "approved", record);
            assertThat(result).isNull();
        }

        @Test
        void shouldViolateWhenValuesDiffer() {
            Map<String, Object> rule = Map.of("field", "status", "operator", "==");
            Map<String, Object> record = Map.of("status", "draft");
            PolicyViolation result = evaluator.evaluate("requiredStatus", rule, "approved", record);
            assertThat(result).isNotNull();
            assertThat(result.message()).contains("must equal", "approved", "draft");
        }

        @Test
        void shouldCompareNumbersAsStrings() {
            Map<String, Object> rule = Map.of("field", "code", "operator", "==");
            Map<String, Object> record = Map.of("code", 42);
            PolicyViolation result = evaluator.evaluate("exactCode", rule, "42", record);
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("!= operator")
    class NotEqualOperator {

        @Test
        void shouldPassWhenValuesDiffer() {
            Map<String, Object> rule = Map.of("field", "category", "operator", "!=");
            Map<String, Object> record = Map.of("category", "standard");
            PolicyViolation result = evaluator.evaluate("blockedCategory", rule, "restricted", record);
            assertThat(result).isNull();
        }

        @Test
        void shouldViolateWhenValuesMatch() {
            Map<String, Object> rule = Map.of("field", "category", "operator", "!=");
            Map<String, Object> record = Map.of("category", "restricted");
            PolicyViolation result = evaluator.evaluate("blockedCategory", rule, "restricted", record);
            assertThat(result).isNotNull();
            assertThat(result.message()).contains("must not equal", "restricted");
        }
    }

    // ========================================================================
    // List operators: in, not_in
    // ========================================================================

    @Nested
    @DisplayName("in operator")
    class InOperator {

        @Test
        void shouldPassWhenValueInList() {
            Map<String, Object> rule = Map.of("field", "status", "operator", "in");
            Map<String, Object> record = Map.of("status", "pending");
            List<String> allowed = List.of("pending", "draft");
            PolicyViolation result = evaluator.evaluate("allowedStatuses", rule, allowed, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldViolateWhenValueNotInList() {
            Map<String, Object> rule = Map.of("field", "status", "operator", "in");
            Map<String, Object> record = Map.of("status", "cancelled");
            List<String> allowed = List.of("pending", "draft");
            PolicyViolation result = evaluator.evaluate("allowedStatuses", rule, allowed, record);
            assertThat(result).isNotNull();
            assertThat(result.message()).contains("cancelled", "not in allowed list");
        }

        @Test
        void shouldHandleSingleValueAsList() {
            Map<String, Object> rule = Map.of("field", "status", "operator", "in");
            Map<String, Object> record = Map.of("status", "active");
            PolicyViolation result = evaluator.evaluate("allowedStatuses", rule, "active", record);
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("not_in operator")
    class NotInOperator {

        @Test
        void shouldPassWhenValueNotInList() {
            Map<String, Object> rule = Map.of("field", "role", "operator", "not_in");
            Map<String, Object> record = Map.of("role", "admin");
            List<String> restricted = List.of("guest", "readonly");
            PolicyViolation result = evaluator.evaluate("blockedRoles", rule, restricted, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldViolateWhenValueInList() {
            Map<String, Object> rule = Map.of("field", "role", "operator", "not_in");
            Map<String, Object> record = Map.of("role", "guest");
            List<String> restricted = List.of("guest", "readonly");
            PolicyViolation result = evaluator.evaluate("blockedRoles", rule, restricted, record);
            assertThat(result).isNotNull();
            assertThat(result.message()).contains("guest", "restricted list");
        }
    }

    // ========================================================================
    // Legacy fallback (maxXxx / minXxx convention)
    // ========================================================================

    @Nested
    @DisplayName("Legacy convention fallback")
    class LegacyFallback {

        @Test
        void shouldEvaluateMaxConventionWhenNoOperator() {
            // Rule has no operator/field -- triggers legacy path
            Map<String, Object> rule = Map.of("type", "number");
            Map<String, Object> record = Map.of("approvalAmount", 150000);
            PolicyViolation result = evaluator.evaluate("maxApprovalAmount", rule, 100000, record);
            assertThat(result).isNotNull();
            assertThat(result.message()).contains("maxApprovalAmount", "150000", "exceeds", "100000");
        }

        @Test
        void shouldPassMaxConventionWhenWithinLimit() {
            Map<String, Object> rule = Map.of("type", "number");
            Map<String, Object> record = Map.of("approvalAmount", 50000);
            PolicyViolation result = evaluator.evaluate("maxApprovalAmount", rule, 100000, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldEvaluateMinConventionWhenNoOperator() {
            Map<String, Object> rule = Map.of("type", "number");
            Map<String, Object> record = Map.of("orderQuantity", 0);
            PolicyViolation result = evaluator.evaluate("minOrderQuantity", rule, 1, record);
            assertThat(result).isNotNull();
            assertThat(result.message()).contains("minOrderQuantity", "0", "below", "1");
        }

        @Test
        void shouldPassMinConventionWhenAboveLimit() {
            Map<String, Object> rule = Map.of("type", "number");
            Map<String, Object> record = Map.of("orderQuantity", 5);
            PolicyViolation result = evaluator.evaluate("minOrderQuantity", rule, 1, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldFallbackWhenRuleIsEmptyMap() {
            // Empty rule map -- no operator or field, triggers legacy
            Map<String, Object> rule = Map.of();
            Map<String, Object> record = Map.of("approvalAmount", 200000);
            PolicyViolation result = evaluator.evaluate("maxApprovalAmount", rule, 100000, record);
            assertThat(result).isNotNull();
        }
    }

    // ========================================================================
    // Null and edge cases
    // ========================================================================

    @Nested
    @DisplayName("Null and edge cases")
    class NullAndEdgeCases {

        @Test
        void shouldPassWhenRecordValueIsNull() {
            Map<String, Object> rule = Map.of("field", "amount", "operator", "<=");
            Map<String, Object> record = Map.of("other", 100);
            PolicyViolation result = evaluator.evaluate("maxAmount", rule, 100000, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldHandleStringNumberCoercion() {
            Map<String, Object> rule = Map.of("field", "amount", "operator", "<=");
            Map<String, Object> record = Map.of("amount", "50000");
            PolicyViolation result = evaluator.evaluate("maxAmount", rule, 100000, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldHandleNonNumericStringInComparison() {
            Map<String, Object> rule = Map.of("field", "amount", "operator", "<=");
            Map<String, Object> record = Map.of("amount", "not-a-number");
            PolicyViolation result = evaluator.evaluate("maxAmount", rule, 100000, record);
            // Non-numeric strings skip comparison (pass)
            assertThat(result).isNull();
        }

        @Test
        void shouldReturnNullForUnknownOperator() {
            Map<String, Object> rule = Map.of("field", "x", "operator", "~=");
            Map<String, Object> record = Map.of("x", 5);
            PolicyViolation result = evaluator.evaluate("rule", rule, 10, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldHandleDoubleValues() {
            Map<String, Object> rule = Map.of("field", "rate", "operator", "<=");
            Map<String, Object> record = Map.of("rate", 0.15);
            PolicyViolation result = evaluator.evaluate("maxRate", rule, 0.2, record);
            assertThat(result).isNull();
        }

        @Test
        void shouldViolateWithDoubleValues() {
            Map<String, Object> rule = Map.of("field", "rate", "operator", "<=");
            Map<String, Object> record = Map.of("rate", 0.25);
            PolicyViolation result = evaluator.evaluate("maxRate", rule, 0.2, record);
            assertThat(result).isNotNull();
        }
    }
}
