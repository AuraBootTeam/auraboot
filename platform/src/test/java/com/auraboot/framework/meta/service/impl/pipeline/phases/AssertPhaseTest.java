package com.auraboot.framework.meta.service.impl.pipeline.phases;

import org.junit.jupiter.api.Test;

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
}
