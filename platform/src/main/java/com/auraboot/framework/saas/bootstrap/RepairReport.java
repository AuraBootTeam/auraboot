package com.auraboot.framework.saas.bootstrap;

import java.util.List;

/**
 * Aggregated report from {@link BootstrapRepairService#repairAll}.
 *
 * <p>Caller can use {@link #anyError()} to decide whether bootstrap failed and
 * {@link #totalCreated()} / {@link #totalRepaired()} to log a one-line summary.
 */
public record RepairReport(
        List<RepairStepResult> steps,
        int totalPresent,
        int totalCreated,
        int totalRepaired,
        boolean anyError) {

    public static RepairReport from(List<RepairStepResult> steps) {
        int present = 0, created = 0, repaired = 0;
        boolean error = false;
        for (RepairStepResult r : steps) {
            switch (r.status()) {
                case PRESENT -> present++;
                case CREATED -> created++;
                case REPAIRED -> repaired++;
                case ERROR -> error = true;
            }
        }
        return new RepairReport(List.copyOf(steps), present, created, repaired, error);
    }
}
