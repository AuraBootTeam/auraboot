package com.auraboot.plugins.crm.engine;

/**
 * Pure customer-journey enrollment progression (CRM gap #6, M1 slice).
 *
 * <p>Decides what happens when an enrollment is advanced: move to the next step,
 * complete (no steps left), or no-op (already in a terminal state). Separated
 * from the handler so the state machine is unit-testable without a Spring context
 * or database.
 *
 * <p>State model: an enrollment has a 0-based {@code currentStep}, a journey with
 * {@code totalSteps}, and a {@code status} of {@code active} / {@code completed} /
 * {@code exited}. Only {@code active} enrollments advance; the last step completes.
 */
public final class JourneyProgressionEngine {

    private JourneyProgressionEngine() {
    }

    public static final String ACTIVE = "active";
    public static final String COMPLETED = "completed";
    public static final String EXITED = "exited";

    /** What the caller should do with the enrollment. */
    public enum Action {
        ADVANCE, COMPLETE, NOOP
    }

    public record Input(int currentStep, int totalSteps, String status) {
    }

    public record Result(Action action, int nextStep, String nextStatus) {
    }

    public static Result progress(Input in) {
        String status = in.status() == null ? "" : in.status();
        // Terminal states never advance.
        if (!ACTIVE.equals(status)) {
            return new Result(Action.NOOP, in.currentStep(), status.isEmpty() ? EXITED : status);
        }
        int total = Math.max(0, in.totalSteps());
        int current = Math.max(0, in.currentStep());
        // A journey with no real steps, or already at/past the last step, completes.
        if (total <= 1 || current >= total - 1) {
            return new Result(Action.COMPLETE, current, COMPLETED);
        }
        return new Result(Action.ADVANCE, current + 1, ACTIVE);
    }
}
