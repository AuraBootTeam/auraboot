package com.auraboot.framework.meta.validation;

import com.auraboot.framework.meta.dto.CrossFieldRule;
import com.auraboot.framework.meta.dto.RuleAssert;
import com.auraboot.framework.meta.dto.RuleOverride;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleMergerTest {

    @Test
    void mergeWithNoOverrides_returnsOriginalRules() {
        var rule = makeRule("r1", "amount", "gte");
        var result = RuleMerger.merge(List.of(rule), List.of());
        assertEquals(1, result.size());
        assertEquals("r1", result.get(0).getId());
    }

    @Test
    void mergeWithNullOverrides_returnsOriginalRules() {
        var rule = makeRule("r1", "amount", "gte");
        var result = RuleMerger.merge(List.of(rule), null);
        assertEquals(1, result.size());
    }

    @Test
    void mergeWithDisabledOverride_removesRule() {
        var rule = makeRule("r1", "amount", "gte");
        var override = new RuleOverride();
        override.setId("r1");
        override.setDisabled(true);
        var result = RuleMerger.merge(List.of(rule), List.of(override));
        assertTrue(result.isEmpty());
    }

    @Test
    void mergeWithReplacementOverride_replacesRule() {
        var rule = makeRule("r1", "amount", "gte");
        rule.setMessage("original");
        var replacement = makeOverride("r1", "amount", "lte");
        replacement.setMessage("replaced");
        var result = RuleMerger.merge(List.of(rule), List.of(replacement));
        assertEquals(1, result.size());
        assertEquals("replaced", result.get(0).getMessage());
    }

    @Test
    void mergeWithNewOverride_appendsRule() {
        var rule = makeRule("r1", "amount", "gte");
        var newRule = makeOverride("r2", "name", "required");
        var result = RuleMerger.merge(List.of(rule), List.of(newRule));
        assertEquals(2, result.size());
        assertEquals("r1", result.get(0).getId());
        assertEquals("r2", result.get(1).getId());
    }

    @Test
    void disabledTakesPrecedenceOverAssert() {
        var rule = makeRule("r1", "amount", "gte");
        var override = makeOverride("r1", "amount", "lte");
        override.setDisabled(true);
        var result = RuleMerger.merge(List.of(rule), List.of(override));
        assertTrue(result.isEmpty());
    }

    @Test
    void mixedOverrides_disableReplaceAppend() {
        var r1 = makeRule("r1", "a", "gte");
        var r2 = makeRule("r2", "b", "gte");
        var r3 = makeRule("r3", "c", "gte");

        var disableR1 = new RuleOverride();
        disableR1.setId("r1");
        disableR1.setDisabled(true);

        var replaceR2 = makeOverride("r2", "b", "lte");
        replaceR2.setMessage("replaced-r2");

        var addR4 = makeOverride("r4", "d", "required");

        var result = RuleMerger.merge(List.of(r1, r2, r3), List.of(disableR1, replaceR2, addR4));
        assertEquals(3, result.size());
        // r1 disabled, r2 replaced, r3 untouched, r4 appended
        assertEquals("r2", result.get(0).getId());
        assertEquals("replaced-r2", result.get(0).getMessage());
        assertEquals("r3", result.get(1).getId());
        assertEquals("r4", result.get(2).getId());
    }

    // --- helpers ---

    private CrossFieldRule makeRule(String id, String field, String operator) {
        var rule = new CrossFieldRule();
        rule.setId(id);
        var a = new RuleAssert();
        a.setField(field);
        if ("gte".equals(operator)) a.setGte(0);
        if ("lte".equals(operator)) a.setLte(100);
        if ("required".equals(operator)) a.setRequired(true);
        rule.setRuleAssert(a);
        rule.setMessage("test-" + id);
        return rule;
    }

    private RuleOverride makeOverride(String id, String field, String operator) {
        var override = new RuleOverride();
        override.setId(id);
        var a = new RuleAssert();
        a.setField(field);
        if ("gte".equals(operator)) a.setGte(0);
        if ("lte".equals(operator)) a.setLte(100);
        if ("required".equals(operator)) a.setRequired(true);
        override.setRuleAssert(a);
        override.setMessage("test-" + id);
        return override;
    }
}
