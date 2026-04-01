package com.auraboot.framework.organization.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request to transfer an employee to a new department and/or position.
 */
@Data
public class TransferRequest {

    @NotBlank(message = "New department is required")
    private String newDeptPid;

    private String newPositionPid;
}
