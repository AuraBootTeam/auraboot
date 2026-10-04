package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleAssert;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CrossFieldRuleEngineMessageTest {
    @ParameterizedTest
    @ValueSource(strings = {"$5", "$1", "C:\\temporary\\receipt", "trailing\\", "{other}"})
    void placeholderValuesAreLiteralAndCannotSuppressFailedValidation(String amount) {
        var assertion = new RuleAssert();
        assertion.setField("name");
        assertion.setRequired(true);
        var rule = new CrossFieldRule();
        rule.setId("required-name");
        rule.setRuleAssert(assertion);
        rule.setMessage("Name required for payment {amount}");
        var result = new CrossFieldRuleEngine().evaluate(List.of(rule), List.of(), Map.of("amount", amount));
        assertTrue(result.hasErrors(), "Formatting a data value must not discard the violation");
        assertEquals(1, result.errors().size());
        assertEquals("Name required for payment " + amount, result.errors().get(0).message());
        assertEquals("name", result.errors().get(0).targetField());
    }
}
