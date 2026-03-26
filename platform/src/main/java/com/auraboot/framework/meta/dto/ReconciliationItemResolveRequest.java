package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for resolving a reconciliation item.
 */
@Data
public class ReconciliationItemResolveRequest {

    @NotBlank(message = "Resolution is required")
    private String resolution;

    private String notes;
}
