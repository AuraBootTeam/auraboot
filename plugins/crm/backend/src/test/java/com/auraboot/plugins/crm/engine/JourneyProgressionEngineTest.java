package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-standard coverage for {@link JourneyProgressionEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #6 M1).
 */
class JourneyProgressionEngineTest {

    private static JourneyProgressionEngine.Result progress(int cur, int total, String status) {
        return JourneyProgressionEngine.progress(new JourneyProgressionEngine.Input(cur, total, status));
    }

    // ---------- happy path ----------
    @Test
    void activeMidJourneyAdvancesToNextStep() {
        var r = progress(0, 3, "active");
        assertEquals(JourneyProgressionEngine.Action.ADVANCE, r.action());
        assertEquals(1, r.nextStep());
        assertEquals("active", r.nextStatus());
    }

    @Test
    void activeAdvancesAcrossEachInteriorStep() {
        var r = progress(1, 3, "active");
        assertEquals(JourneyProgressionEngine.Action.ADVANCE, r.action());
        assertEquals(2, r.nextStep());
    }

    // ---------- sad / terminal path ----------
    @Test
    void completedEnrollmentIsNoop() {
        var r = progress(2, 3, "completed");
        assertEquals(JourneyProgressionEngine.Action.NOOP, r.action());
        assertEquals("completed", r.nextStatus());
        assertEquals(2, r.nextStep());
    }

    @Test
    void exitedEnrollmentIsNoop() {
        var r = progress(1, 3, "exited");
        assertEquals(JourneyProgressionEngine.Action.NOOP, r.action());
        assertEquals("exited", r.nextStatus());
    }

    // ---------- edge case ----------
    @Test
    void lastStepCompletesJourney() {
        var r = progress(2, 3, "active");
        assertEquals(JourneyProgressionEngine.Action.COMPLETE, r.action());
        assertEquals("completed", r.nextStatus());
    }

    // ---------- corner cases ----------
    @Test
    void singleStepJourneyCompletesImmediately() {
        var r = progress(0, 1, "active");
        assertEquals(JourneyProgressionEngine.Action.COMPLETE, r.action());
        assertEquals("completed", r.nextStatus());
    }

    @Test
    void currentStepBeyondTotalCompletes() {
        var r = progress(9, 3, "active");
        assertEquals(JourneyProgressionEngine.Action.COMPLETE, r.action());
    }

    @Test
    void blankStatusIsTreatedAsTerminalNoop() {
        var r = progress(0, 3, "");
        assertEquals(JourneyProgressionEngine.Action.NOOP, r.action());
        assertEquals("exited", r.nextStatus());
    }
}
