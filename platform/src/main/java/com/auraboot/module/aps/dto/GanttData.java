package com.auraboot.module.aps.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data @AllArgsConstructor @NoArgsConstructor
public class GanttData {
    private String strategy;
    private List<GanttRow> rows;
    private List<GanttTask> tasks;
    private List<GanttMilestone> milestones;
    private Map<Long, Double> resourceUtilization;
    private List<ScheduleConflict> conflicts;
}
