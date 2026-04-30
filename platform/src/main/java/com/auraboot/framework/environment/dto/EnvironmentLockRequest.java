package com.auraboot.framework.environment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for lock / unlock operations on an environment.
 * Reason is mandatory and surfaced in audit trail.
 */
@Data
public class EnvironmentLockRequest {

    @NotBlank(message = "reason must not be blank")
    @Size(max = 500, message = "reason must be 500 characters or fewer")
    private String reason;
}
