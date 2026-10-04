package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuleStaticValidatorBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"eq", "neq", "gt", "gte", "lt", "lte", "in", "notIn", "required", "maxLength", "minLength", "pattern"})
    void eachSupportedOperatorIsSufficient(String operator) {
        var a = new RuleAssert();
        a.setField("amount");
        switch (operator) {
            case "eq" -> a.setEq(1);
            case "neq" -> a.setNeq(1);
            case "gt" -> a.setGt(1);
            case "gte" -> a.setGte(1);
            case "lt" -> a.setLt(1);
            case "lte" -> a.setLte(1);
            case "in" -> a.setIn(List.of(1));
            case "notIn" -> a.setNotIn(List.of(1));
            case "required" -> a.setRequired(true);
            case "maxLength" -> a.setMaxLength(1);
            case "minLength" -> a.setMinLength(1);
            case "pattern" -> a.setPattern(".+");
        }
        assertEquals(List.of(), validate(a));
    }

    @Test
    void allComparisonReferencesAreCheckedAndKnownOrLiteralMapsAreAccepted() {
        var a = new RuleAssert();
        a.setField("amount");
        a.setEq(Map.of("ref", "missingEq"));
        a.setNeq(Map.of("ref", "missingNeq"));
        a.setGt(Map.of("ref", "amount"));
        a.setGte(Map.of("literal", 1));
        a.setLt(Map.of("ref", "missingLt"));
        a.setLte(Map.of("ref", "missingLte"));
        assertEquals(List.of(
            "Rule references unknown field: missingEq in rule: boundary",
            "Rule references unknown field: missingNeq in rule: boundary",
            "Rule references unknown field: missingLt in rule: boundary",
            "Rule references unknown field: missingLte in rule: boundary"), validate(a));
    }

    @Test
    void notInRejectsNullEntriesButAcceptsEmptyArrays() {
        var a = new RuleAssert();
        a.setField("amount");
        a.setIn(List.of());
        a.setNotIn(Arrays.asList(1, null));
        assertEquals(List.of("in/notIn arrays must not contain null in rule: boundary"), validate(a));
        a.setNotIn(List.of());
        assertEquals(List.of(), validate(a));
    }

    @Test
    void missingAssertionBlankMessageAndEmptyExpressionDependenciesAreReported() {
        var rule = rule(null);
        rule.setMessage(" ");
        assertEquals(List.of("Rule message is required for rule: boundary", "Rule assert is required for rule: boundary"),
            RuleStaticValidator.validate(List.of(rule), Set.of("amount")));
        var a = new RuleAssert();
        a.setExpr("amount > 0");
        rule = rule(a);
        rule.setDependsOn(List.of());
        assertEquals(List.of("Expression rules require explicit dependsOn in rule: boundary"),
            RuleStaticValidator.validate(List.of(rule), Set.of("amount")));
    }

    @Test
    void requiredFalseDoesNotDeclareAnOperator() {
        var a = new RuleAssert();
        a.setField("amount");
        a.setRequired(false);
        assertEquals(List.of("Assert must have at least one operator in rule: boundary"), validate(a));
    }

    private List<String> validate(RuleAssert assertion) {
        return RuleStaticValidator.validate(List.of(rule(assertion)), Set.of("amount"));
    }
    private CrossFieldRule rule(RuleAssert assertion) {
        var rule = new CrossFieldRule();
        rule.setId("boundary");
        rule.setMessage("Invalid amount");
        rule.setRuleAssert(assertion);
        return rule;
    }
}
