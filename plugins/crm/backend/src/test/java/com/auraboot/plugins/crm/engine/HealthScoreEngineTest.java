package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-standard coverage for {@link HealthScoreEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #10).
 */
class HealthScoreEngineTest {

    private static HealthScoreEngine.Result compute(int complaints, int won, int open, int days) {
        return HealthScoreEngine.compute(new HealthScoreEngine.Signals(complaints, won, open, days));
    }

    // ---------- happy path ----------
    @Test
    void healthyAccountScoresHigh() {
        // 70 + min(3*5,20)=15 + min(2*3,15)=6 - 0 - 0(recent) = 91
        var r = compute(0, 3, 2, 5);
        assertEquals(91, r.score());
        assertEquals("healthy", r.band());
    }

    // ---------- sad path ----------
    @Test
    void manyComplaintsStaleScoresCritical() {
        // 70 - min(4*15,45)=45 - 20(>90d) = 5
        var r = compute(4, 0, 0, 120);
        assertEquals(5, r.score());
        assertEquals("critical", r.band());
    }

    // ---------- edge case ----------
    @Test
    void neverEngagedNewAccountIsAtRisk() {
        // 70 - 10(never) = 60
        var r = compute(0, 0, 0, -1);
        assertEquals(60, r.score());
        assertEquals("at_risk", r.band());
    }

    // ---------- corner cases ----------
    @Test
    void positiveSignalsClampAt100() {
        // 70 + 20(won cap) + 15(open cap) = 105 -> clamp 100
        var r = compute(0, 10, 10, 1);
        assertEquals(100, r.score());
        assertEquals("healthy", r.band());
    }

    @Test
    void bandBoundaryAt70IsHealthy() {
        // exactly base, recent, no signals = 70 (>=70 healthy)
        var r = compute(0, 0, 0, 5);
        assertEquals(70, r.score());
        assertEquals("healthy", r.band());
    }

    @Test
    void bandBoundaryAt40IsAtRisk() {
        // 70 - min(2*15,45)=30 = 40 (>=40 at_risk, inclusive lower bound)
        var r = compute(2, 0, 0, 5);
        assertEquals(40, r.score());
        assertEquals("at_risk", r.band());
    }

    @Test
    void justBelow40IsCritical() {
        // 70 - 45(3 complaints) = 25 (<40 critical)
        var r = compute(3, 0, 0, 5);
        assertEquals(25, r.score());
        assertEquals("critical", r.band());
    }

    @Test
    void scoreClampsAtZeroFloor() {
        // 70 - 45 - 20 = 5; push lower impossible via caps, assert never negative
        var r = compute(9, 0, 0, 365);
        assertEquals(5, r.score());
        assertEquals("critical", r.band());
    }
}
