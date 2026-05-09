package com.auraboot.framework.saas.bootstrap;

/**
 * Result of a single {@link BootstrapRepairService} step.
 *
 * <p>Each repair step is idempotent and returns one of four statuses:
 * <ul>
 *   <li>{@link Status#PRESENT} — invariant already held; no write performed</li>
 *   <li>{@link Status#CREATED} — invariant was missing; created from scratch</li>
 *   <li>{@link Status#REPAIRED} — invariant was partially present; re-aligned</li>
 *   <li>{@link Status#ERROR} — repair failed (caller can decide whether to abort)</li>
 * </ul>
 */
public record RepairStepResult(String stepName, Status status, String detail) {

    public enum Status {
        PRESENT,
        CREATED,
        REPAIRED,
        ERROR
    }

    public static RepairStepResult present(String stepName, String detail) {
        return new RepairStepResult(stepName, Status.PRESENT, detail);
    }

    public static RepairStepResult created(String stepName, String detail) {
        return new RepairStepResult(stepName, Status.CREATED, detail);
    }

    public static RepairStepResult repaired(String stepName, String detail) {
        return new RepairStepResult(stepName, Status.REPAIRED, detail);
    }

    public static RepairStepResult error(String stepName, String detail) {
        return new RepairStepResult(stepName, Status.ERROR, detail);
    }
}
