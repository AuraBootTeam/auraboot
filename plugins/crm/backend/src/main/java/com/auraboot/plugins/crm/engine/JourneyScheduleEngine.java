package com.auraboot.plugins.crm.engine;

/**
 * Pure scheduling decision for journey auto-advance (CRM gap #6, M2 core).
 *
 * <p>Decides whether an active enrollment is "due" to advance to its next step,
 * based on how long it has dwelt on the current step versus that step's
 * configured delay. Separated from the (future) scheduled job so the timing rule
 * is unit-testable without a Spring context, clock, or database.
 *
 * <p>An enrollment is due iff it is {@code active} and the elapsed time since its
 * last advance is at least the current step's delay. A non-positive delay means
 * advance immediately; a non-active enrollment is never due.
 */
public final class JourneyScheduleEngine {

    private JourneyScheduleEngine() {
    }

    /**
     * @param status              enrollment status (only {@code active} can be due)
     * @param lastAdvancedAtEpochMin epoch-minute the enrollment last advanced / enrolled
     * @param stepDelayMinutes    delay configured for the current step
     * @param nowEpochMin         current epoch-minute
     */
    public record Input(String status, long lastAdvancedAtEpochMin, int stepDelayMinutes, long nowEpochMin) {
    }

    public static boolean isDue(Input in) {
        if (!JourneyProgressionEngine.ACTIVE.equals(in.status())) {
            return false;
        }
        int delay = Math.max(0, in.stepDelayMinutes());
        if (delay == 0) {
            return true;
        }
        long elapsed = in.nowEpochMin() - in.lastAdvancedAtEpochMin();
        return elapsed >= delay;
    }
}
