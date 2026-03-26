package com.auraboot.framework.bpm.engine.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Engine-agnostic representation of a process history entry.
 */
@Data
@Builder
public class HistoryRecord {

    private String id;
    private String activityId;
    private String activityName;
    private String activityType;
    private String processInstanceId;
    private String executedBy;
    private Map<String, Object> variables;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMillis;
}
