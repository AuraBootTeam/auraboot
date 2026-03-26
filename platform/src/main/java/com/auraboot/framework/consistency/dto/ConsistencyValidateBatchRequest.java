package com.auraboot.framework.consistency.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for batch consistency validation.
 */
@Data
public class ConsistencyValidateBatchRequest {

    @NotBlank(message = "Model code is required")
    private String modelCode;

    @NotEmpty(message = "Record IDs are required")
    private List<String> recordIds;
}
