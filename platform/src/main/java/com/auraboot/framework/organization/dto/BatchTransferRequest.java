package com.auraboot.framework.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request to batch transfer multiple employees to a new department/position.
 */
public record BatchTransferRequest(
    @NotEmpty(message = "Employee PIDs list must not be empty")
    List<String> employeePids,

    @NotBlank(message = "New department is required")
    String newDeptPid,

    String newPositionPid
) {}
