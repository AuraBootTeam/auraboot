package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.RuleAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssertEvaluatorBoundaryTest {
    @ParameterizedTest
    @CsvSource({"eq,10,10,true", "eq,10,11,false", "neq,10,11,true", "neq,10,10,false",
        "gt,11,10,true", "gt,10,10,false", "gte,10,10,true", "gte,9,10,false",
        "lt,9,10,true", "lt,10,10,false", "lte,10,10,true", "lte,11,10,false"})
    void numericComparisonsReturnTheFailedOperator(String operator, int left, double right, boolean expected) {
        var result = AssertEvaluator.evaluate(assertion(operator, right), Map.of("value", left));
        assertEquals(expected, result.passed());
        assertFalse(result.skipped());
        assertEquals(expected ? null : operator, result.failedOperator());
    }

    @ParameterizedTest
    @ValueSource(strings = {"eq", "neq", "gt", "gte", "lt", "lte"})
    void absentReferenceValuesSkipEveryComparison(String operator) {
        var result = AssertEvaluator.evaluate(assertion(operator, Map.of("ref", "missing")), Map.of("value", 10));
        assertTrue(result.passed());
        assertTrue(result.skipped());
        assertNull(result.failedOperator());
    }

    @Test
    void stringConstraintsDoNotRejectNonStringValues() {
        var value = new RuleAssert();
        value.setField("value");
        value.setMaxLength(1);
        value.setMinLength(100);
        value.setPattern("[a-z]+");
        assertEquals(AssertEvaluator.AssertResult.PASSED, AssertEvaluator.evaluate(value, Map.of("value", 10)));
    }

    @Test
    void explicitNonRequiredNullIsSkippedAndNonStringRequiredValuesPass() {
        var value = new RuleAssert();
        value.setField("value");
        value.setRequired(false);
        assertEquals(AssertEvaluator.AssertResult.SKIPPED, AssertEvaluator.evaluate(value, Map.of()));
        value.setRequired(true);
        assertEquals(AssertEvaluator.AssertResult.PASSED, AssertEvaluator.evaluate(value, Map.of("value", false)));
        assertEquals(AssertEvaluator.AssertResult.PASSED, AssertEvaluator.evaluate(value, Map.of("value", 0)));
    }

    @Test
    void missingFieldAndEmptyConstraintsHaveDefinedResults() {
        assertEquals(AssertEvaluator.AssertResult.PASSED, AssertEvaluator.evaluate(new RuleAssert(), Map.of()));
        var value = new RuleAssert();
        value.setField("value");
        assertEquals(AssertEvaluator.AssertResult.PASSED, AssertEvaluator.evaluate(value, Map.of("value", 10)));
    }

    @Test
    void membershipsSupportNumericCoercionAndLiteralText() {
        var value = new RuleAssert();
        value.setField("value");
        value.setIn(List.of("text", 10L, "10"));
        assertTrue(AssertEvaluator.evaluate(value, Map.of("value", 10.0)).passed());
        assertTrue(AssertEvaluator.evaluate(value, Map.of("value", "text")).passed());
        assertTrue(AssertEvaluator.evaluate(value, Map.of("value", "10")).passed());
        assertEquals("in", AssertEvaluator.evaluate(value, Map.of("value", 20)).failedOperator());
        value.setIn(List.of());
        assertEquals("in", AssertEvaluator.evaluate(value, Map.of("value", "text")).failedOperator());
        value.setIn(null);
        value.setNotIn(List.of("text", 10L));
        assertEquals("notIn", AssertEvaluator.evaluate(value, Map.of("value", 10.0)).failedOperator());
        assertEquals("notIn", AssertEvaluator.evaluate(value, Map.of("value", "text")).failedOperator());
        assertTrue(AssertEvaluator.evaluate(value, Map.of("value", "10")).passed());
        assertTrue(AssertEvaluator.evaluate(value, Map.of("value", 20)).passed());
        value.setNotIn(List.of());
        assertTrue(AssertEvaluator.evaluate(value, Map.of("value", "text")).passed());
    }

    private static RuleAssert assertion(String operator, Object right) {
        var value = new RuleAssert();
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
