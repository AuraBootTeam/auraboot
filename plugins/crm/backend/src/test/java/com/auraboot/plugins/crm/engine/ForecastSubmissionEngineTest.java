package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-standard coverage for {@link ForecastSubmissionEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #5b).
 */
class ForecastSubmissionEngineTest {

    private static ForecastSubmissionEngine.Submission sub(String commit, String best, String pipeline) {
        return new ForecastSubmissionEngine.Submission(
                new BigDecimal(commit), new BigDecimal(best), new BigDecimal(pipeline), "2026-Q2");
    }

    // ---------- happy path ----------
    @Test
    void monotonicAmountsAreValid() {
        var r = ForecastSubmissionEngine.validate(sub("100", "150", "200"));
        assertTrue(r.valid());
        assertTrue(r.violations().isEmpty());
    }

    // ---------- sad path ----------
    @Test
    void commitAboveBestCaseIsRejected() {
        var r = ForecastSubmissionEngine.validate(sub("160", "150", "200"));
        assertFalse(r.valid());
        assertTrue(r.violations().contains("commit must not exceed best_case"));
    }

    @Test
    void bestCaseAbovePipelineIsRejected() {
        var r = ForecastSubmissionEngine.validate(sub("100", "210", "200"));
        assertFalse(r.valid());
        assertTrue(r.violations().contains("best_case must not exceed pipeline"));
    }

    @Test
    void blankPeriodIsRejected() {
        var r = ForecastSubmissionEngine.validate(new ForecastSubmissionEngine.Submission(
                new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1"), "  "));
        assertFalse(r.valid());
        assertTrue(r.violations().contains("period is required"));
    }

    @Test
    void nullSubmissionIsRejected() {
        var r = ForecastSubmissionEngine.validate(null);
        assertFalse(r.valid());
        assertTrue(r.violations().contains("submission is null"));
    }

    // ---------- edge case ----------
    @Test
    void allZeroIsValid() {
        var r = ForecastSubmissionEngine.validate(sub("0", "0", "0"));
        assertTrue(r.valid());
    }

    @Test
    void nullAmountsTreatedAsZeroAndValid() {
        var r = ForecastSubmissionEngine.validate(
                new ForecastSubmissionEngine.Submission(null, null, null, "2026-Q2"));
        assertTrue(r.valid());
    }

    // ---------- corner case ----------
    @Test
    void equalBoundaryAmountsAreValid() {
        // commit == bestCase == pipeline is the inclusive boundary
        var r = ForecastSubmissionEngine.validate(sub("200", "200", "200"));
        assertTrue(r.valid());
    }

    @Test
    void negativeAmountsFlagBothSignAndOrderViolations() {
        // commit -1 > best -2 > pipeline -3: negative + monotonicity both broken
        var r = ForecastSubmissionEngine.validate(sub("-1", "-2", "-3"));
        assertFalse(r.valid());
        assertTrue(r.violations().contains("commit amount must be >= 0"));
        assertTrue(r.violations().contains("commit must not exceed best_case"));
    }
}
