package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.RuleCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {

    // --- Single condition operators ---

    @Test
    void eq_matches() {
        var cond = cond("priority", "eq", "urgent");
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("priority", "urgent")));
    }

    @Test
    void eq_notMatches() {
        var cond = cond("priority", "eq", "urgent");
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("priority", "low")));
    }

    @Test
    void neq_matches() {
        var cond = cond("status", "neq", "draft");
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("status", "active")));
    }

    @Test
    void gt_numeric() {
        var cond = cond("amount", "gt", 10000);
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("amount", 20000)));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("amount", 5000)));
    }

    @Test
    void gte_numeric() {
        var cond = cond("amount", "gte", 100);
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("amount", 100)));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("amount", 99)));
    }

    @Test
    void lt_numeric() {
        var cond = cond("age", "lt", 18);
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("age", 10)));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("age", 20)));
    }

    @Test
    void lte_numeric() {
        var cond = cond("age", "lte", 18);
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("age", 18)));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("age", 19)));
    }

    @Test
    void in_matches() {
        var cond = new RuleCondition();
        cond.setField("status");
        cond.setIn(List.of("active", "pending"));
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("status", "active")));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("status", "draft")));
    }

    @Test
    void notIn_matches() {
        var cond = new RuleCondition();
        cond.setField("status");
        cond.setNotIn(List.of("deleted", "archived"));
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("status", "active")));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("status", "deleted")));
    }

    // --- Ref comparison ---

    @Test
    void gt_withRef() {
        var cond = cond("endDate", "gt", Map.of("ref", "startDate"));
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("endDate", "2026-05-01", "startDate", "2026-03-01")));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("endDate", "2026-01-01", "startDate", "2026-03-01")));
    }

    // --- Null handling ---

    @Test
    void nullField_returnsFalse() {
        var cond = cond("priority", "eq", "urgent");
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of()));
    }

    @Test
    void nullRefField_returnsFalse() {
        var cond = cond("endDate", "gt", Map.of("ref", "startDate"));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("endDate", "2026-05-01")));
    }

    // --- Compound conditions ---

    @Test
    void and_allTrue() {
        var cond = new RuleCondition();
        cond.setAnd(List.of(
            cond("priority", "eq", "urgent"),
            cond("amount", "gt", 10000)
        ));
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("priority", "urgent", "amount", 20000)));
    }

    @Test
    void and_oneFalse() {
        var cond = new RuleCondition();
        cond.setAnd(List.of(
            cond("priority", "eq", "urgent"),
            cond("amount", "gt", 10000)
        ));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("priority", "urgent", "amount", 5000)));
    }

    @Test
    void or_oneTrue() {
        var cond = new RuleCondition();
        cond.setOr(List.of(
            cond("priority", "eq", "urgent"),
            cond("priority", "eq", "high")
        ));
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("priority", "high")));
    }

    @Test
    void or_allFalse() {
        var cond = new RuleCondition();
        cond.setOr(List.of(
            cond("priority", "eq", "urgent"),
            cond("priority", "eq", "high")
        ));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("priority", "low")));
    }

    @Test
    void not_inverts() {
        var inner = cond("status", "eq", "draft");
        var cond = new RuleCondition();
        cond.setNot(inner);
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("status", "active")));
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("status", "draft")));
    }

    @Test
    void nested_orContainingAnd() {
        var cond = new RuleCondition();
        var andPart = new RuleCondition();
        andPart.setAnd(List.of(
            cond("type", "eq", "external"),
            cond("amount", "gt", 5000)
        ));
        cond.setOr(List.of(
            cond("priority", "eq", "urgent"),
            andPart
        ));
        // urgent → true
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("priority", "urgent", "type", "internal", "amount", 100)));
        // external + amount>5000 → true
        assertTrue(ConditionEvaluator.evaluate(cond, Map.of("priority", "low", "type", "external", "amount", 10000)));
        // neither → false
        assertFalse(ConditionEvaluator.evaluate(cond, Map.of("priority", "low", "type", "internal", "amount", 100)));
    }

    // --- Expression mode ---

    @Test
    void expressionMode_throwsUnsupported() {
        var cond = new RuleCondition();
        cond.setExpr("amount > 100");
        assertThrows(UnsupportedOperationException.class,
            () -> ConditionEvaluator.evaluate(cond, Map.of("amount", 200)));
    }

    // --- Helper ---

    private RuleCondition cond(String field, String op, Object value) {
        var c = new RuleCondition();
        c.setField(field);
        switch (op) {
            case "eq" -> c.setEq(value);
            case "neq" -> c.setNeq(value);
            case "gt" -> c.setGt(value);
            case "gte" -> c.setGte(value);
            case "lt" -> c.setLt(value);
            case "lte" -> c.setLte(value);
        }
        return c;
    }
}
