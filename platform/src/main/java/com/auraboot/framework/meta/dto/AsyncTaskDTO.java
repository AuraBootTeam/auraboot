package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO for async task responses.
 */
@Data
public class AsyncTaskDTO {

    private String taskCode;
    private String taskType;
    private String taskName;
    private String status;
    private Integer priority;
    private Integer progress;
    private String progressMessage;
    private JsonNode inputParams;
    private JsonNode resultData;
    private String errorMessage;
    private Integer retryCount;
    private Integer maxRetries;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private Integer timeoutSeconds;
}
