package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleAssert;
import com.auraboot.framework.meta.dto.RuleCondition;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrossFieldRuleEngineBoundaryTest {
    @Test
    void emptyRulesAndMissingAssertionProduceNoViolations() {
        var engine = new CrossFieldRuleEngine();
        assertEquals(List.of(), engine.evaluate(null, null, Map.of()).errors());
        assertEquals(List.of(), engine.evaluate(List.of(new CrossFieldRule()), null, Map.of()).errors());
    }

    @Test
    void messagesResolveTranslationFallbackAndMissingPlaceholders() {
        var engine = new CrossFieldRuleEngine(null, key -> key.equals("required") ? "Missing {name}" : null);
        assertEquals("Validation failed", engine.resolveMessage(null, Map.of()));
        assertEquals("Missing name", engine.resolveMessage("$i18n:required", Map.of()));
        assertEquals("unknown", engine.resolveMessage("$i18n:unknown", Map.of()));
        assertEquals("required", new CrossFieldRuleEngine().resolveMessage("$i18n:required", Map.of()));
    }

    @Test
    void failedExpressionWithoutTargetIsAFormLevelViolation() {
        var engine = new CrossFieldRuleEngine(expr -> false);
        var result = engine.evaluate(List.of(expressionRule()), null, Map.of());
        assertEquals(1, result.errors().size());
        assertNull(result.errors().get(0).targetField());
        assertEquals("Invalid formula", result.errors().get(0).message());
    }

    @Test
    void nullExpressionResultFailsAndFalseWhenSkips() {
        var engine = new CrossFieldRuleEngine(expr -> null);
        assertEquals(1, engine.evaluate(List.of(expressionRule()), null, Map.of()).errors().size());
        var rule = expressionRule();
        var when = new RuleCondition();
        when.setExpr("falseCondition");
        rule.setWhen(when);
        assertEquals(List.of(), engine.evaluate(List.of(rule), null, Map.of()).errors());
    }

    @Test
    void expressionFailuresFollowExistingSkipContractAndDoNotSkipLaterRules() {
        var engine = new CrossFieldRuleEngine(expr -> { throw new IllegalArgumentException("Bad formula"); });
        assertEquals(List.of(), engine.evaluate(List.of(expressionRule()), null, Map.of()).errors());
        var required = new CrossFieldRule();
        required.setId("required");
        required.setMessage("Name required");
        var assertion = new RuleAssert();
        assertion.setField("name");
        assertion.setRequired(true);
        required.setRuleAssert(assertion);
        var result = new CrossFieldRuleEngine().evaluate(List.of(expressionRule(), required), null, Map.of());
        assertEquals(List.of("required"), result.errors().stream().map(RuleViolation::ruleId).toList());
    }

    private CrossFieldRule expressionRule() {
        var rule = new CrossFieldRule();
        rule.setId("formula");
        rule.setMessage("Invalid formula");
        var assertion = new RuleAssert();
        assertion.setExpr("total == amount * quantity");
        rule.setRuleAssert(assertion);
        return rule;
    }
}
