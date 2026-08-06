package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-standard coverage for {@link JourneyScheduleEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #6 M2 core).
 */
class JourneyScheduleEngineTest {

    private static boolean isDue(String status, long last, int delay, long now) {
        return JourneyScheduleEngine.isDue(new JourneyScheduleEngine.Input(status, last, delay, now));
    }

    // ---------- happy path ----------
    @Test
    void activeAndDelayElapsedIsDue() {
        // enrolled at minute 100, 60-min delay, now 200 -> elapsed 100 >= 60
        assertTrue(isDue("active", 100, 60, 200));
    }

    // ---------- sad path ----------
    @Test
    void activeButDelayNotElapsedIsNotDue() {
        // enrolled at 100, 60-min delay, now 130 -> elapsed 30 < 60
        assertFalse(isDue("active", 100, 60, 130));
    }

    @Test
    void completedEnrollmentIsNeverDue() {
        assertFalse(isDue("completed", 0, 0, 9999));
    }

    @Test
    void exitedEnrollmentIsNeverDue() {
        assertFalse(isDue("exited", 0, 60, 9999));
    }

    // ---------- edge case ----------
    @Test
    void exactlyAtDelayBoundaryIsDue() {
        // elapsed == delay (60) -> inclusive, due
        assertTrue(isDue("active", 100, 60, 160));
    }

    // ---------- corner cases ----------
    @Test
    void zeroDelayAdvancesImmediately() {
        assertTrue(isDue("active", 200, 0, 200));
    }

    @Test
    void negativeDelayTreatedAsImmediate() {
        assertTrue(isDue("active", 200, -5, 200));
    }

    @Test
    void oneMinuteBeforeBoundaryIsNotDue() {
        assertFalse(isDue("active", 100, 60, 159));
    }
}
