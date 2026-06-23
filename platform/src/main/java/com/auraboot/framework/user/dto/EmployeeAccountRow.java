package com.auraboot.framework.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * One employee row from a customer account list.
 */
@Data
public class EmployeeAccountRow {

    @NotBlank(message = "Employee name is required")
    @Size(max = 64)
    private String name;

    @NotBlank(message = "Employee type is required")
    @Size(max = 32)
    private String type;

    @Email(message = "Invalid email format")
    private String email;

    @Size(max = 64)
    private String mobile;
}
