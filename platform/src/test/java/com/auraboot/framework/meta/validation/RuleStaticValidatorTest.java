package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleAssert;
import com.auraboot.framework.meta.dto.RuleCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuleStaticValidatorTest {

    private final Set<String> knownFields = Set.of("amount", "startDate", "endDate", "priority", "deadline", "name");

    @Test
    void validDeclarativeRule_noErrors() {
        var rule = rule("r1", assertField("amount", "gte", 0), "Error msg");
        var errors = RuleStaticValidator.validate(List.of(rule), knownFields);
        assertTrue(errors.isEmpty());
    }

    @Test
    void validExpressionRule_noErrors() {
        var rule = rule("r1", assertExpr("endDate > startDate"), "Error msg");
        rule.setDependsOn(List.of("endDate", "startDate"));
        var errors = RuleStaticValidator.validate(List.of(rule), knownFields);
        assertTrue(errors.isEmpty());
    }

    @Test
    void missingId_error() {
        var rule = rule(null, assertField("amount", "gte", 0), "Error");
        var errors = RuleStaticValidator.validate(List.of(rule), knownFields);
        assertTrue(errors.stream().anyMatch(e -> e.contains("id is required")));
    }

    @Test
    void emptyId_error() {
        var rule = rule("", assertField("amount", "gte", 0), "Error");
        var errors = RuleStaticValidator.validate(List.of(rule), knownFields);
        assertTrue(errors.stream().anyMatch(e -> e.contains("id is required")));
    }

    @Test
    void missingMessage_error() {
        var rule = rule("r1", assertField("amount", "gte", 0), null);
        var errors = RuleStaticValidator.validate(List.of(rule), knownFields);
        assertTrue(errors.stream().anyMatch(e -> e.contains("message is required")));
    }

    @Test
    void duplicateId_error() {
        var r1 = rule("dup", assertField("amount", "gte", 0), "Msg1");
        var r2 = rule("dup", assertField("name", "required", true), "Msg2");
        var errors = RuleStaticValidator.validate(List.of(r1, r2), knownFields);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate rule id")));
    }

    @Test
    void bothFieldAndExpr_error() {
        var a = new RuleAssert();
        a.setField("amount");
        a.setExpr("amount > 0");
        a.setGte(0);
        var rule = rule("r1", a, "Error");
        var errors = RuleStaticValidator.validate(List.of(rule), knownFields);
        assertTrue(errors.stream().anyMatch(e -> e.contains("both declarative and expression")));
    }

    @Test
    void declarativeAssertNoOperator_error() {
        var a = new RuleAssert();
        a.setField("amount"); // No operator set
        var rule = rule("r1", a, "Error");
        var errors = RuleStaticValidator.validate(List.of(rule), knownFields);
        assertTrue(errors.stream().anyMatch(e -> e.contains("at least one operator")));
    }

    @Test
    void expressionMissingDependsOn_error() {
        var rule = rule("r1", assertExpr("endDate > startDate"), "Error");
        // No dependsOn set
        var errors = RuleStaticValidator.validate(List.of(rule), knownFields);
        assertTrue(errors.stream().anyMatch(e -> e.contains("dependsOn")));
    }

    @Test
    void refUnknownField_error() {
        var a = new RuleAssert();
        a.setField("endDate");
        a.setGt(java.util.Map.of("ref", "nonExistentField"));
        var rule = rule("r1", a, "Error");
        var errors = RuleStaticValidator.validate(List.of(rule), knownFields);
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown field")));
    }

    @Test
    void inArrayWithNull_error() {
        var a = new RuleAssert();
        a.setField("priority");
        var list = new java.util.ArrayList<>();
        list.add("urgent");
        list.add(null);
        a.setIn(list);
        var rule = rule("r1", a, "Error");
        var errors = RuleStaticValidator.validate(List.of(rule), knownFields);
        assertTrue(errors.stream().anyMatch(e -> e.contains("must not contain null")));
    }

    // --- Helpers ---

    private CrossFieldRule rule(String id, RuleAssert a, String message) {
        var rule = new CrossFieldRule();
        rule.setId(id);
        rule.setRuleAssert(a);
        rule.setMessage(message);
        return rule;
    }

    private RuleAssert assertField(String field, String op, Object value) {
        var a = new RuleAssert();
        a.setField(field);
        switch (op) {
            case "gte" -> a.setGte(value);
            case "required" -> a.setRequired((Boolean) value);
        }
        return a;
    }

    private RuleAssert assertExpr(String expr) {
        var a = new RuleAssert();
        a.setExpr(expr);
        return a;
    }
}
