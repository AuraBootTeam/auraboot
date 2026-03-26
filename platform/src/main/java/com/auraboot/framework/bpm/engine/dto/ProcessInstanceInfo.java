package com.auraboot.framework.bpm.engine.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Engine-agnostic representation of a process instance.
 */
@Data
@Builder
public class ProcessInstanceInfo {

    private String processInstanceId;
    private String processDefinitionKey;
    private String businessKey;
    private ProcessStatus status;
    private Map<String, Object> variables;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String startedBy;

    public enum ProcessStatus {
        RUNNING,
        SUSPENDED,
        COMPLETED,
        CANCELLED
    }
}
