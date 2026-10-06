package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.RuleCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorBoundaryTest {

    @ParameterizedTest
    @CsvSource({
        "eq,10,10,true", "eq,10,11,false", "neq,10,11,true", "neq,10,10,false",
        "gt,11,10,true", "gt,10,10,false", "gte,10,10,true", "gte,9,10,false",
        "lt,9,10,true", "lt,10,10,false", "lte,10,10,true", "lte,11,10,false"
    })
    void numericOperatorsCoerceNumberTypes(String operator, int left, double right, boolean expected) {
        assertEquals(expected, ConditionEvaluator.evaluate(condition(operator, right), Map.of("value", left)));
    }

    @ParameterizedTest
    @CsvSource({
        "eq,a,a,true", "eq,a,b,false", "neq,a,b,true", "neq,a,a,false",
        "gt,b,a,true", "gt,a,a,false", "gte,a,a,true", "gte,a,b,false",
        "lt,a,b,true", "lt,a,a,false", "lte,a,a,true", "lte,b,a,false"
    })
    void comparableOperatorsHandleEqualityAndOrdering(String operator, String left, String right, boolean expected) {
        assertEquals(expected, ConditionEvaluator.evaluate(condition(operator, right), Map.of("value", left)));
    }

    @ParameterizedTest
    @CsvSource({"eq,10,10,true", "neq,10,10,false", "gt,20,10,true", "gte,10,10,true",
        "lt,10,20,true", "lte,20,10,false"})
    void incompatibleComparableTypesUseTheDocumentedTextFallback(String operator, int left, String right, boolean expected) {
        assertEquals(expected, ConditionEvaluator.evaluate(condition(operator, right), Map.of("value", left)));
    }

    @Test
    void nonComparableValuesSupportEqualityAndInequality() {
        record Key(String code) {}
        var value = new Key("same");
        assertTrue(ConditionEvaluator.evaluate(condition("eq", new Key("same")), Map.of("value", value)));
        assertFalse(ConditionEvaluator.evaluate(condition("eq", new Key("different")), Map.of("value", value)));
        assertTrue(ConditionEvaluator.evaluate(condition("neq", new Key("different")), Map.of("value", value)));
        assertFalse(ConditionEvaluator.evaluate(condition("neq", new Key("same")), Map.of("value", value)));
        assertFalse(ConditionEvaluator.evaluate(condition("gt", new Key("same")), Map.of("value", value)));
    }

    @Test
    void unknownOperatorsCannotProduceAMatch() {
        assertFalse(ConditionEvaluator.compareOp(10, 10, "unknown"));
        assertFalse(ConditionEvaluator.compareOp("same", "same", "unknown"));
        assertFalse(ConditionEvaluator.compareOp(new Object(), new Object(), "unknown"));
    }

    @Test
    void membershipCoercesNumbersButDoesNotTreatTextAsANumber() {
        var value = new RuleCondition();
        value.setField("value");
        value.setIn(List.of("10", 10L, 20.0));
        assertTrue(ConditionEvaluator.evaluate(value, Map.of("value", 10)));
        assertTrue(ConditionEvaluator.evaluate(value, Map.of("value", 20)));
        assertFalse(ConditionEvaluator.evaluate(value, Map.of("value", 30)));
        value.setIn(List.of("10"));
        assertFalse(ConditionEvaluator.evaluate(value, Map.of("value", 10)));
        value.setIn(List.of());
        assertFalse(ConditionEvaluator.evaluate(value, Map.of("value", 10)));
    }

    @Test
    void notInRejectsMatchingNumbersAndAllowsValuesOutsideTheList() {
        var value = new RuleCondition();
        value.setField("value");
        value.setNotIn(List.of(10L, "ignored"));
        assertFalse(ConditionEvaluator.evaluate(value, Map.of("value", 10.0)));
        assertTrue(ConditionEvaluator.evaluate(value, Map.of("value", 20)));
        value.setNotIn(List.of());
        assertTrue(ConditionEvaluator.evaluate(value, Map.of("value", 10)));
    }

    @Test
    void membershipChecksTextValuesWithoutNumericCoercion() {
        var value = new RuleCondition();
        value.setField("value");
        value.setIn(List.of("accepted"));
        assertTrue(ConditionEvaluator.evaluate(value, Map.of("value", "accepted")));
        assertFalse(ConditionEvaluator.evaluate(value, Map.of("value", "rejected")));
        value.setIn(null);
        value.setNotIn(List.of("rejected"));
        assertTrue(ConditionEvaluator.evaluate(value, Map.of("value", "accepted")));
        assertFalse(ConditionEvaluator.evaluate(value, Map.of("value", "rejected")));
    }

    @Test
    void literalMapsAreNotMistakenForReferences() {
        var literal = Map.of("code", "same");
        assertTrue(ConditionEvaluator.evaluate(condition("eq", literal), Map.of("value", Map.of("code", "same"))));
        assertFalse(ConditionEvaluator.evaluate(condition("eq", literal), Map.of("value", Map.of("code", "other"))));
    }

    @Test
    void missingReferencesRemainFalseForEqualityAndInequality() {
        for (var operator : List.of("eq", "neq", "gt", "gte", "lt", "lte")) {
            assertFalse(ConditionEvaluator.evaluate(condition(operator, Map.of("ref", "missing")), Map.of("value", 10)));
        }
    }

    @Test
    void unconstrainedAndEmptyCompoundConditionsHaveDefinedResults() {
        assertTrue(ConditionEvaluator.evaluate(new RuleCondition(), Map.of()));
        var value = new RuleCondition();
        value.setField("value");
        assertTrue(ConditionEvaluator.evaluate(value, Map.of("value", 10)));
        var and = new RuleCondition();
        and.setAnd(List.of());
        assertTrue(ConditionEvaluator.evaluate(and, Map.of()));
        var or = new RuleCondition();
        or.setOr(List.of());
        assertFalse(ConditionEvaluator.evaluate(or, Map.of()));
    }

    private static RuleCondition condition(String operator, Object right) {
        var value = new RuleCondition();
        value.setField("value");
        switch (operator) {
            case "eq" -> value.setEq(right);
            case "neq" -> value.setNeq(right);
            case "gt" -> value.setGt(right);
            case "gte" -> value.setGte(right);
            case "lt" -> value.setLt(right);
            case "lte" -> value.setLte(right);
            default -> throw new IllegalArgumentException(operator);
        }
        return value;
    }
}
