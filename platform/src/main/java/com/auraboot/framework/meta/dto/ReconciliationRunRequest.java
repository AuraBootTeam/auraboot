package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * Request DTO for starting a reconciliation run.
 */
@Data
public class ReconciliationRunRequest {

    @NotNull(message = "Profile ID is required")
    private Long profileId;

    private LocalDate periodStart;
    private LocalDate periodEnd;
}
