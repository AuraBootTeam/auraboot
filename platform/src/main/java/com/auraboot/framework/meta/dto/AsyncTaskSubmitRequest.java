package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for submitting an async task.
 */
@Data
public class AsyncTaskSubmitRequest {

    @NotBlank(message = "taskType is required")
    private String taskType;

    @NotBlank(message = "taskName is required")
    private String taskName;

    private Integer priority;

    private JsonNode inputParams;

    private Integer maxRetries;

    private Integer timeoutSeconds;
}
