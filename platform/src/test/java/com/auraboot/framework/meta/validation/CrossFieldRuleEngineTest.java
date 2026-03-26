package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleAssert;
import com.auraboot.framework.meta.dto.RuleCondition;
import com.auraboot.framework.meta.dto.RuleOverride;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CrossFieldRuleEngineTest {

    private final CrossFieldRuleEngine engine = new CrossFieldRuleEngine();

    @Test
    void noWhen_assertPasses_noError() {
        var rule = rule("r1", null, assertField("amount", "gte", 0), "Amount must be >= 0");
        var result = engine.evaluate(List.of(rule), List.of(), Map.of("amount", 100));
        assertFalse(result.hasErrors());
    }

    @Test
    void noWhen_assertFails_errorCollected() {
        var rule = rule("r1", null, assertField("amount", "gte", 0), "Amount must be >= 0");
        var result = engine.evaluate(List.of(rule), List.of(), Map.of("amount", -1));
        assertTrue(result.hasErrors());
        assertEquals(1, result.errors().size());
        assertEquals("r1", result.errors().get(0).ruleId());
        assertEquals("amount", result.errors().get(0).targetField());
    }

    @Test
    void whenFalse_ruleSkipped() {
        var when = cond("priority", "eq", "urgent");
        var rule = rule("r1", when, assertField("deadline", "required", true), "Deadline required");
        var result = engine.evaluate(List.of(rule), List.of(), Map.of("priority", "low"));
        assertFalse(result.hasErrors());
    }

    @Test
    void whenTrue_assertFails_errorCollected() {
        var when = cond("priority", "eq", "urgent");
        var rule = rule("r1", when, assertField("deadline", "required", true), "Deadline required");
        var data = Map.<String, Object>of("priority", "urgent"); // deadline missing
        var result = engine.evaluate(List.of(rule), List.of(), data);
        assertTrue(result.hasErrors());
        assertEquals("deadline", result.errors().get(0).targetField());
    }

    @Test
    void severityWarning_noBlockingError() {
        var rule = rule("r1", null, assertField("amount", "gte", 0), "Warning");
        rule.setSeverity("warning");
        var result = engine.evaluate(List.of(rule), List.of(), Map.of("amount", -1));
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertEquals("warning", result.warnings().get(0).severity());
    }

    @Test
    void multipleRules_mixedErrorsAndWarnings() {
        var errorRule = rule("r1", null, assertField("a", "gte", 0), "Error");
        var warningRule = rule("r2", null, assertField("b", "gte", 0), "Warning");
        warningRule.setSeverity("warning");

        var result = engine.evaluate(List.of(errorRule, warningRule), List.of(),
            Map.of("a", -1, "b", -1));
        assertTrue(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertEquals(1, result.errors().size());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void commandOverride_disablesRule() {
        var rule = rule("r1", null, assertField("amount", "gte", 0), "Error");
        var override = new RuleOverride();
        override.setId("r1");
        override.setDisabled(true);

        var result = engine.evaluate(List.of(rule), List.of(override), Map.of("amount", -1));
        assertFalse(result.hasErrors());
    }

    @Test
    void messagePlaceholder_resolved() {
        // resolveMessage is an instance method, not static
        var engine = new CrossFieldRuleEngine(null);
        var msg = engine.resolveMessage("End date must be after {startDate}", Map.of("startDate", "2026-01-01"));
        assertEquals("End date must be after 2026-01-01", msg);
    }

    @Test
    void expressionWhen_withSpelEvaluator() {
        // SpEL evaluator that checks "amount > 10000"
        CrossFieldRuleEngine exprEngine = new CrossFieldRuleEngine(expr -> {
            // Simple mock — only handles "amount > 10000"
            return true; // Simulate condition met
        });

        var when = new RuleCondition();
        when.setExpr("amount > 10000");
        var rule = rule("r1", when, assertField("approver", "required", true), "Approver required");

        var data = Map.<String, Object>of("amount", 20000); // approver missing
        var result = exprEngine.evaluate(List.of(rule), List.of(), data);
        assertTrue(result.hasErrors());
    }

    @Test
    void expressionAssert_withSpelEvaluator() {
        CrossFieldRuleEngine exprEngine = new CrossFieldRuleEngine(expr -> false); // Always fails
        var assertExpr = new RuleAssert();
        assertExpr.setExpr("totalAmount == unitPrice * quantity");
        var rule = new CrossFieldRule();
        rule.setId("r1");
        rule.setRuleAssert(assertExpr);
        rule.setMessage("Total mismatch");
        rule.setTargetField("totalAmount");

        var result = exprEngine.evaluate(List.of(rule), List.of(), Map.of("totalAmount", 100));
        assertTrue(result.hasErrors());
        assertEquals("totalAmount", result.errors().get(0).targetField());
    }

    @Test
    void nullFieldInAssert_skipped() {
        var rule = rule("r1", null, assertField("endDate", "gt", Map.of("ref", "startDate")), "Error");
        // endDate is null → rule skipped
        var result = engine.evaluate(List.of(rule), List.of(), Map.of("startDate", "2026-01-01"));
        assertFalse(result.hasErrors());
    }

    // --- Helpers ---

    private CrossFieldRule rule(String id, RuleCondition when, RuleAssert ruleAssert, String message) {
        var rule = new CrossFieldRule();
        rule.setId(id);
        rule.setWhen(when);
        rule.setRuleAssert(ruleAssert);
        rule.setMessage(message);
        return rule;
    }

    private RuleCondition cond(String field, String op, Object value) {
        var c = new RuleCondition();
        c.setField(field);
        switch (op) {
            case "eq" -> c.setEq(value);
            case "gt" -> c.setGt(value);
        }
        return c;
    }

    private RuleAssert assertField(String field, String op, Object value) {
        var a = new RuleAssert();
        a.setField(field);
        switch (op) {
            case "gte" -> a.setGte(value);
            case "gt" -> a.setGt(value);
            case "required" -> a.setRequired((Boolean) value);
        }
        return a;
    }
}
