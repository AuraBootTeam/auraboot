package com.auraboot.framework.meta.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleEvaluationResultTest {
    @Test
    void nullAndEmptyCollectionsHaveNoErrorsOrWarnings() {
        for (var result : List.of(new RuleEvaluationResult(null, null), new RuleEvaluationResult(List.of(), List.of()))) {
            assertFalse(result.hasErrors());
            assertFalse(result.hasWarnings());
            assertEquals("", result.formatErrorMessages());
        }
    }

    @Test
    void errorsAreJoinedInOrderAndWarningsRemainIndependent() {
        var first = new RuleViolation("first", "amount", "Amount invalid", "error");
        var second = new RuleViolation("second", "status", "Status invalid", "error");
        var warning = new RuleViolation("warning", "name", "Name warning", "warning");
        var result = new RuleEvaluationResult(List.of(first, second), List.of(warning));
        assertTrue(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertEquals("Amount invalid; Status invalid", result.formatErrorMessages());
        assertEquals(List.of(warning), result.warnings());
    }
}
