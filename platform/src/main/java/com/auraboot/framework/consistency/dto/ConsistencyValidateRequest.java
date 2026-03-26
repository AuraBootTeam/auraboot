package com.auraboot.framework.consistency.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for manual consistency validation trigger.
 */
@Data
public class ConsistencyValidateRequest {

    @NotBlank(message = "Model code is required")
    private String modelCode;

    @NotBlank(message = "Record ID is required")
    private String recordId;
}
