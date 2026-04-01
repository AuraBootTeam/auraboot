package com.auraboot.framework.organization.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request to create a new employee with associated user and tenant member.
 */
@Data
public class CreateEmployeeRequest {

    @NotBlank(message = "Employee name is required")
    private String name;

    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Phone is required")
    private String phone;

    private String gender;

    @NotBlank(message = "Department is required")
    private String deptPid;

    private String positionPid;

    private String managerPid;
}
