package com.auraboot.framework.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * BPM process statistics for the workbench dashboard.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkbenchBpmStatsDTO {
    private double completionRate;
    private double avgDurationHours;
    private double overdueRate;
    private int runningCount;
    private int completedThisWeek;
    private int completedLastWeek;
}
