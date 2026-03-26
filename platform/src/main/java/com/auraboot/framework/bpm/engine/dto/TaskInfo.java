package com.auraboot.framework.bpm.engine.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Engine-agnostic representation of a user task.
 */
@Data
@Builder
public class TaskInfo {

    private String taskId;
    private String taskName;
    private String taskDefinitionKey;
    private String processInstanceId;
    private String assignee;
    private String candidateGroup;
    private Map<String, Object> variables;
    private LocalDateTime createTime;
    private LocalDateTime dueDate;
}
