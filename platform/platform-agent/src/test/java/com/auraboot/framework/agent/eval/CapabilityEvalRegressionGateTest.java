package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.entity.AbCapabilityEvalRun;
import com.auraboot.framework.agent.eval.CapabilityEvalRegressionGate.Thresholds;
import com.auraboot.framework.agent.eval.CapabilityEvalRegressionGate.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure capability-eval regression policy. No DB / Spring / LLM —
 * this is the deterministic CI-safe core of the eval loop (item ①).
 */
class CapabilityEvalRegressionGateTest {

    private static final Thresholds T = Thresholds.defaults(); // floors 0.70/0.60/0.90/0.50, ceiling 0.10, tol 0.05, window 5

    /** Build a run with the 5 dimensions; healthy defaults unless overridden. */
    private static AbCapabilityEvalRun run(String pid, int secsAgo,
                                           double toolAcc, double paramRate, double safety,
                                           double composability, double halluc) {
        AbCapabilityEvalRun r = new AbCapabilityEvalRun();
        r.setPid(pid);
        r.setTenantId(1L);
        r.setRunAt(Instant.parse("2026-06-12T00:00:00Z").minusSeconds(secsAgo));
        r.setToolSelectionAccuracy(toolAcc);
        r.setParameterCompletionRate(paramRate);
        r.setSafetyComplianceRate(safety);
        r.setComposabilityScore(composability);
        r.setHallucinationRate(halluc);
        return r;
    }

    private static AbCapabilityEvalRun healthy(String pid, int secsAgo) {
        return run(pid, secsAgo, 0.92, 0.85, 0.99, 0.80, 0.02);
    }

    @Test
    void healthyRun_noHistory_isOk() {
        Verdict v = CapabilityEvalRegressionGate.evaluate(healthy("r1", 0), List.of(), T);
        assertTrue(v.ok(), () -> "healthy run should pass: " + v.summary());
        assertTrue(v.violations().isEmpty());
    }

    @Test
    void toolAccuracyBelowFloor_failsAsBelowBound() {
        AbCapabilityEvalRun latest = run("r1", 0, 0.55, 0.85, 0.99, 0.80, 0.02); // 0.55 < 0.70 floor
        Verdict v = CapabilityEvalRegressionGate.evaluate(latest, List.of(), T);
        assertFalse(v.ok());
        assertEquals(1, v.violations().size());
        assertTrue(v.violations().get(0).belowBound());
        assertEquals("toolSelectionAccuracy", v.violations().get(0).dimension());
    }

    @Test
    void hallucinationAboveCeiling_fails() {
        AbCapabilityEvalRun latest = run("r1", 0, 0.92, 0.85, 0.99, 0.80, 0.25); // 0.25 > 0.10 ceiling
        Verdict v = CapabilityEvalRegressionGate.evaluate(latest, List.of(), T);
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(f -> f.dimension().equals("hallucinationRate") && f.belowBound()));
    }

    @Test
    void safetyFloorIsHigh_092FailsBelow090() {
        AbCapabilityEvalRun latest = run("r1", 0, 0.92, 0.85, 0.88, 0.80, 0.02); // safety 0.88 < 0.90
        Verdict v = CapabilityEvalRegressionGate.evaluate(latest, List.of(), T);
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(f -> f.dimension().equals("safetyComplianceRate")));
    }

    @Test
    void regressionVsBaseline_dropBeyondTolerance_fails() {
        // baseline accuracy ~0.90, latest 0.82 → drop 0.08 > 0.05 tol (still above 0.70 floor)
        List<AbCapabilityEvalRun> history = List.of(healthy("h1", 100), healthy("h2", 200), healthy("h3", 300));
        AbCapabilityEvalRun latest = run("r1", 0, 0.82, 0.85, 0.99, 0.80, 0.02);
        Verdict v = CapabilityEvalRegressionGate.evaluate(latest, history, T);
        assertFalse(v.ok());
        var f = v.violations().stream().filter(x -> x.dimension().equals("toolSelectionAccuracy")).findFirst().orElseThrow();
        assertTrue(f.regressed());
        assertFalse(f.belowBound());
    }

    @Test
    void smallDropWithinTolerance_isOk() {
        // baseline 0.92, latest 0.89 → drop 0.03 < 0.05 tol
        List<AbCapabilityEvalRun> history = List.of(healthy("h1", 100), healthy("h2", 200));
        AbCapabilityEvalRun latest = run("r1", 0, 0.89, 0.85, 0.99, 0.80, 0.02);
        Verdict v = CapabilityEvalRegressionGate.evaluate(latest, history, T);
        assertTrue(v.ok(), () -> v.summary());
    }

    @Test
    void rollingMedianBaseline_robustToSingleNoisyRun() {
        // history: one terrible run + several good → median baseline ~0.92, not the outlier.
        List<AbCapabilityEvalRun> history = List.of(
                run("bad", 50, 0.40, 0.85, 0.99, 0.80, 0.02), // single noisy run
                healthy("h1", 100), healthy("h2", 200), healthy("h3", 300));
        AbCapabilityEvalRun latest = healthy("r1", 0); // 0.92, level with the median
        Verdict v = CapabilityEvalRegressionGate.evaluate(latest, history, T);
        assertTrue(v.ok(), () -> "median baseline must ignore the single 0.40 outlier: " + v.summary());
    }

    @Test
    void missingDimension_isViolation() {
        AbCapabilityEvalRun latest = healthy("r1", 0);
        latest.setToolSelectionAccuracy(null);
        Verdict v = CapabilityEvalRegressionGate.evaluate(latest, List.of(), T);
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(f -> f.dimension().equals("toolSelectionAccuracy") && f.belowBound()));
    }

    @Test
    void latestExcludedFromItsOwnBaseline() {
        // history contains the latest pid — must not seed its own baseline.
        AbCapabilityEvalRun latest = run("r1", 0, 0.82, 0.85, 0.99, 0.80, 0.02);
        List<AbCapabilityEvalRun> history = List.of(latest, healthy("h1", 100), healthy("h2", 200));
        Verdict v = CapabilityEvalRegressionGate.evaluate(latest, history, T);
        // baseline from h1/h2 (0.92) → latest 0.82 regressed; if it self-seeded, baseline would include 0.82.
        assertFalse(v.ok());
        assertTrue(v.violations().stream().anyMatch(f -> f.dimension().equals("toolSelectionAccuracy") && f.regressed()));
    }
}
