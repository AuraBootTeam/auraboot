package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentOnlineEvalService.OnlineEvalSummary;
import com.auraboot.framework.agent.eval.OnlineEvalQualityGate.Thresholds;
import com.auraboot.framework.agent.eval.OnlineEvalQualityGate.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure L4 online-eval quality gate: given an aggregate summary over
 * sampled production turns, it flags degradation below configured bounds. No DB / Spring /
 * LLM — the gate is a pure function (mirrors {@link CapabilityEvalRegressionGate} for L3).
 */
class OnlineEvalQualityGateTest {

    /** Production-grade default bounds. */
    private static final Thresholds T = new Thresholds(0.80, 0.20, 0.20, 0.50);

    private static OnlineEvalSummary summary(int n, double healthy, double fail,
                                             double cost, double avg) {
        return new OnlineEvalSummary("heuristic", n, healthy, fail, cost, avg, List.of());
    }

    @Test
    void healthySummaryPasses() {
        Verdict v = OnlineEvalQualityGate.evaluate(summary(20, 0.95, 0.05, 0.05, 0.85), T);
        assertTrue(v.ok());
        assertTrue(v.violations().isEmpty());
    }

    @Test
    void lowHealthyRateIsFlagged() {
        Verdict v = OnlineEvalQualityGate.evaluate(summary(20, 0.60, 0.10, 0.05, 0.70), T);
        assertFalse(v.ok());
        assertEquals(1, v.violations().size());
        assertEquals("healthyRate", v.violations().get(0).dimension());
        assertTrue(v.violations().get(0).below());
    }

    @Test
    void highFailRateIsFlagged() {
        Verdict v = OnlineEvalQualityGate.evaluate(summary(20, 0.85, 0.40, 0.05, 0.70), T);
        assertFalse(v.ok());
        assertEquals("failRate", v.violations().get(0).dimension());
        assertFalse(v.violations().get(0).below());
    }

    @Test
    void highCostFlaggedRateIsFlagged() {
        Verdict v = OnlineEvalQualityGate.evaluate(summary(20, 0.85, 0.05, 0.50, 0.70), T);
        assertFalse(v.ok());
        assertEquals("costFlaggedRate", v.violations().get(0).dimension());
    }

    @Test
    void lowAvgScoreIsFlagged() {
        Verdict v = OnlineEvalQualityGate.evaluate(summary(20, 0.85, 0.05, 0.05, 0.30), T);
        assertFalse(v.ok());
        assertEquals("avgScore", v.violations().get(0).dimension());
        assertTrue(v.violations().get(0).below());
    }

    @Test
    void multipleViolationsAllReported() {
        Verdict v = OnlineEvalQualityGate.evaluate(summary(20, 0.50, 0.45, 0.40, 0.20), T);
        assertFalse(v.ok());
        assertEquals(4, v.violations().size());
        assertTrue(v.summary().startsWith("degraded:"));
    }

    @Test
    void emptySampleIsNoOpPass() {
        Verdict v = OnlineEvalQualityGate.evaluate(summary(0, 0, 0, 0, 0), T);
        assertTrue(v.ok());
        assertTrue(v.violations().isEmpty());
        assertEquals("no_sample", v.summary());
    }

    @Test
    void nullSummaryIsNoOpPass() {
        Verdict v = OnlineEvalQualityGate.evaluate(null, T);
        assertTrue(v.ok());
        assertEquals("no_sample", v.summary());
    }

    @Test
    void boundaryValuesAreInclusive() {
        // Exactly at the bound is acceptable: healthy >= min, fail <= max, avg >= min.
        Verdict v = OnlineEvalQualityGate.evaluate(summary(10, 0.80, 0.20, 0.20, 0.50), T);
        assertTrue(v.ok(), "values exactly at the bound must not be flagged");
    }
}
