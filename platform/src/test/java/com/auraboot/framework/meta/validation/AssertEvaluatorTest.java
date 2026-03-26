package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.RuleAssert;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssertEvaluatorTest {

    // --- Required ---

    @Test
    void required_nullFails() {
        var a = assertWith("name", "required", true);
        var result = AssertEvaluator.evaluate(a, mapOf());
        assertFalse(result.passed());
    }

    @Test
    void required_emptyStringFails() {
        var a = assertWith("name", "required", true);
        assertFalse(AssertEvaluator.evaluate(a, mapOf("name", "")).passed());
        assertFalse(AssertEvaluator.evaluate(a, mapOf("name", "  ")).passed());
    }

    @Test
    void required_nonNullPasses() {
        var a = assertWith("name", "required", true);
        assertTrue(AssertEvaluator.evaluate(a, mapOf("name", "Alice")).passed());
    }

    // --- MaxLength / MinLength ---

    @Test
    void maxLength_passes() {
        var a = new RuleAssert();
        a.setField("name");
        a.setMaxLength(10);
        assertTrue(AssertEvaluator.evaluate(a, mapOf("name", "short")).passed());
    }

    @Test
    void maxLength_fails() {
        var a = new RuleAssert();
        a.setField("name");
        a.setMaxLength(3);
        assertFalse(AssertEvaluator.evaluate(a, mapOf("name", "toolong")).passed());
    }

    @Test
    void minLength_passes() {
        var a = new RuleAssert();
        a.setField("name");
        a.setMinLength(3);
        assertTrue(AssertEvaluator.evaluate(a, mapOf("name", "Alice")).passed());
    }

    @Test
    void minLength_fails() {
        var a = new RuleAssert();
        a.setField("name");
        a.setMinLength(5);
        assertFalse(AssertEvaluator.evaluate(a, mapOf("name", "ab")).passed());
    }

    // --- Pattern ---

    @Test
    void pattern_matches() {
        var a = new RuleAssert();
        a.setField("email");
        a.setPattern("^[\\w.]+@[\\w.]+$");
        assertTrue(AssertEvaluator.evaluate(a, mapOf("email", "test@example.com")).passed());
    }

    @Test
    void pattern_notMatches() {
        var a = new RuleAssert();
        a.setField("email");
        a.setPattern("^[\\w.]+@[\\w.]+$");
        assertFalse(AssertEvaluator.evaluate(a, mapOf("email", "not-an-email")).passed());
    }

    // --- Comparison with literal ---

    @Test
    void gte_literal_passes() {
        var a = assertWith("amount", "gte", 0);
        assertTrue(AssertEvaluator.evaluate(a, mapOf("amount", 100)).passed());
        assertTrue(AssertEvaluator.evaluate(a, mapOf("amount", 0)).passed());
    }

    @Test
    void gte_literal_fails() {
        var a = assertWith("amount", "gte", 0);
        assertFalse(AssertEvaluator.evaluate(a, mapOf("amount", -1)).passed());
    }

    // --- Comparison with ref ---

    @Test
    void gt_ref_passes() {
        var a = new RuleAssert();
        a.setField("endDate");
        a.setGt(Map.of("ref", "startDate"));
        assertTrue(AssertEvaluator.evaluate(a, mapOf("endDate", "2026-05-01", "startDate", "2026-03-01")).passed());
    }

    @Test
    void gt_ref_fails() {
        var a = new RuleAssert();
        a.setField("endDate");
        a.setGt(Map.of("ref", "startDate"));
        assertFalse(AssertEvaluator.evaluate(a, mapOf("endDate", "2026-01-01", "startDate", "2026-03-01")).passed());
    }

    // --- Multiple operators (range) ---

    @Test
    void multipleOperators_range_passes() {
        var a = new RuleAssert();
        a.setField("amount");
        a.setGte(0);
        a.setLte(100);
        assertTrue(AssertEvaluator.evaluate(a, mapOf("amount", 50)).passed());
    }

    @Test
    void multipleOperators_range_failsLow() {
        var a = new RuleAssert();
        a.setField("amount");
        a.setGte(0);
        a.setLte(100);
        assertFalse(AssertEvaluator.evaluate(a, mapOf("amount", -1)).passed());
    }

    @Test
    void multipleOperators_range_failsHigh() {
        var a = new RuleAssert();
        a.setField("amount");
        a.setGte(0);
        a.setLte(100);
        assertFalse(AssertEvaluator.evaluate(a, mapOf("amount", 200)).passed());
    }

    // --- Null semantics ---

    @Test
    void nullField_nonRequired_skipped() {
        // Non-required assert with null field → SKIP (passed)
        var a = assertWith("amount", "gte", 0);
        var result = AssertEvaluator.evaluate(a, mapOf());
        assertTrue(result.skipped());
    }

    @Test
    void nullRefField_skipped() {
        var a = new RuleAssert();
        a.setField("endDate");
        a.setGt(Map.of("ref", "startDate"));
        // endDate exists but startDate is null → skip
        var result = AssertEvaluator.evaluate(a, mapOf("endDate", "2026-05-01"));
        assertTrue(result.skipped());
    }

    // --- Expression mode ---

    @Test
    void expressionMode_throwsUnsupported() {
        var a = new RuleAssert();
        a.setExpr("amount > 100");
        assertThrows(UnsupportedOperationException.class,
            () -> AssertEvaluator.evaluate(a, mapOf("amount", 200)));
    }

    // --- In / NotIn ---

    @Test
    void in_passes() {
        var a = new RuleAssert();
        a.setField("status");
        a.setIn(List.of("active", "pending"));
        assertTrue(AssertEvaluator.evaluate(a, mapOf("status", "active")).passed());
    }

    @Test
    void in_fails() {
        var a = new RuleAssert();
        a.setField("status");
        a.setIn(List.of("active", "pending"));
        assertFalse(AssertEvaluator.evaluate(a, mapOf("status", "draft")).passed());
    }

    // --- Helpers ---

    private RuleAssert assertWith(String field, String op, Object value) {
        var a = new RuleAssert();
        a.setField(field);
        switch (op) {
            case "required" -> a.setRequired((Boolean) value);
            case "gte" -> a.setGte(value);
            case "lte" -> a.setLte(value);
            case "gt" -> a.setGt(value);
        }
        return a;
    }

    private Map<String, Object> mapOf(Object... kv) {
        var map = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
