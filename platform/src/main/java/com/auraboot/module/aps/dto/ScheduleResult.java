package com.auraboot.module.aps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ScheduleResult {
    private String strategy;
    private List<ScheduledOperation> operations;
    private List<ScheduleConflict> conflicts;
    private Map<Long, Double> resourceUtilization;
    private LocalDateTime earliestCompletion;
}
