package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.constant.DslRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for precondition operator handling. Regression guard for the bug where command
 * configs author preconditions with QueryOperator op-codes ({@code is_not_null}, {@code ne}, ...)
 * but the evaluator only understood the canonical {@code NOT_NULL}/{@code NEQ} forms, so every
 * such precondition silently fell through to fail-safe and could never pass (e.g. a DFM review
 * could never be completed; an RFQ finalize guard always blocked).
 */
class AssertPhaseTest {

    private final AssertPhase phase = new AssertPhase(null, null, null, null, null);

    @Test
    void normalizesConfigStyleAndCanonicalOperators() {
        // config-style (QueryOperator op-codes)
        assertEquals("NOT_NULL", AssertPhase.normalizeOperator("is_not_null"));
        assertEquals("NULL", AssertPhase.normalizeOperator("is_null"));
        assertEquals("NEQ", AssertPhase.normalizeOperator("ne"));
        assertEquals("EQ", AssertPhase.normalizeOperator("eq"));
        assertEquals("GE", AssertPhase.normalizeOperator("gte"));
        assertEquals("LE", AssertPhase.normalizeOperator("lte"));
        assertEquals("NOT_IN", AssertPhase.normalizeOperator("not_in"));
        // canonical forms still understood
        assertEquals("NOT_NULL", AssertPhase.normalizeOperator("NOT_NULL"));
        assertEquals("NEQ", AssertPhase.normalizeOperator("NEQ"));
        // unknown passes through upper-cased -> the evaluator's default rejects it fail-safe
        assertEquals("BOGUS", AssertPhase.normalizeOperator("bogus"));
        assertEquals("", AssertPhase.normalizeOperator(null));
    }

    @Test
    void isNotNullRecognizesSetAndNullValues() {
        // the DFM-complete bug: is_not_null must pass when the field is set (was always fail-safe)
        assertTrue(phase.evaluatePrecondition("is_not_null", "fail", null));
        assertFalse(phase.evaluatePrecondition("is_not_null", null, null));
    }

    @Test
    void neBlocksOnlyOnTheEqualValue() {
        // the J6 finalize guard: ne 'fail' must pass for pass/conditional/null, fail only on 'fail'
        assertFalse(phase.evaluatePrecondition("ne", "fail", "fail"));         // -> precondition fails -> finalize blocked
        assertTrue(phase.evaluatePrecondition("ne", "pass", "fail"));
        assertTrue(phase.evaluatePrecondition("ne", "conditional", "fail"));
        assertTrue(phase.evaluatePrecondition("ne", null, "fail"));            // "null" != "fail" -> ok
    }

    @Test
    void canonicalOperatorsAndUnknownStillBehave() {
        assertTrue(phase.evaluatePrecondition("NOT_NULL", "x", null));
        assertTrue(phase.evaluatePrecondition("EQ", "a", "a"));
        assertFalse(phase.evaluatePrecondition("EQ", "a", "b"));
        assertFalse(phase.evaluatePrecondition("totally_unknown_op", "x", "y"));   // fail-safe
    }

    @Test
    void newlyImplementedOperatorsBehave() {
        // DR-20260715-A-001: these were declared in DslRegistry.PreconditionOperator but had no
        // runtime branch (fail-safe false) — a command guarded by one was permanently blocked.
        assertTrue(phase.evaluatePrecondition("starts_with", "hello", "he"));
        assertFalse(phase.evaluatePrecondition("starts_with", "hello", "xx"));
        assertTrue(phase.evaluatePrecondition("ends_with", "hello", "lo"));
        assertFalse(phase.evaluatePrecondition("ends_with", "hello", "xx"));
        assertTrue(phase.evaluatePrecondition("like", "hello", "h%o"));   // SQL LIKE: % = any sequence
        assertTrue(phase.evaluatePrecondition("like", "hello", "h_llo")); // _ = single char
        assertFalse(phase.evaluatePrecondition("like", "hello", "h%x"));
        assertTrue(phase.evaluatePrecondition("not_like", "hello", "h%x"));
        assertFalse(phase.evaluatePrecondition("not_like", "hello", "h%o"));
        assertTrue(phase.evaluatePrecondition("between", 5, List.of(1, 10)));   // inclusive [lo, hi]
        assertTrue(phase.evaluatePrecondition("between", 1, List.of(1, 10)));   // boundary
        assertFalse(phase.evaluatePrecondition("between", 50, List.of(1, 10)));
        // not_contains: implemented in runtime, now also declared in the registry
        assertTrue(phase.evaluatePrecondition("not_contains", "abc", "xyz"));
        assertFalse(phase.evaluatePrecondition("not_contains", "abc", "b"));
    }

    @Test
    void everyDeclaredPreconditionOperatorIsImplemented() {
        // DR-20260715-A-001 reconciliation guard. DslRegistry.PreconditionOperator is the
        // introspection contract fed to LLM/agent authoring; every declared code MUST have a runtime
        // branch, else a command guarded by it is permanently blocked (fail-safe false) while
        // introspection advertises it. For each code, feed inputs where a correct impl returns TRUE;
        // an unimplemented op hits the evaluator's default -> false -> this test goes red.
        // A new registry code with no entry here also fails (forces the reconciliation).
        Map<String, Object[]> passInputs = Map.ofEntries(
                Map.entry("EQ", new Object[]{"a", "a"}),
                Map.entry("NE", new Object[]{"a", "b"}),
                Map.entry("GT", new Object[]{5, 3}),
                Map.entry("GE", new Object[]{5, 5}),
                Map.entry("LT", new Object[]{3, 5}),
                Map.entry("LE", new Object[]{5, 5}),
                Map.entry("IN", new Object[]{"a", List.of("a", "b")}),
                Map.entry("not_in", new Object[]{"z", List.of("a", "b")}),
                Map.entry("is_null", new Object[]{null, null}),
                Map.entry("is_not_null", new Object[]{"x", null}),
                Map.entry("between", new Object[]{5, List.of(1, 10)}),
                Map.entry("like", new Object[]{"hello", "h%o"}),
                Map.entry("not_like", new Object[]{"hello", "h%x"}),
                Map.entry("contains", new Object[]{"hello", "ell"}),
                Map.entry("not_contains", new Object[]{"hello", "zzz"}),
                Map.entry("starts_with", new Object[]{"hello", "he"}),
                Map.entry("ends_with", new Object[]{"hello", "lo"}));
        for (String code : DslRegistry.PreconditionOperator.codes()) {
            assertTrue(passInputs.containsKey(code),
                    "PreconditionOperator '" + code + "' has no reconciliation input — add it and verify "
                            + "it is implemented in AssertPhase.evaluatePrecondition.");
            Object[] io = passInputs.get(code);
            assertTrue(phase.evaluatePrecondition(code, io[0], io[1]),
                    "Declared PreconditionOperator '" + code + "' is not implemented in AssertPhase "
                            + "(fell through to fail-safe). Implement its runtime branch or remove it from "
                            + "DslRegistry.PreconditionOperator.");
        }
    }
}
