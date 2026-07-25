package com.auraboot.framework.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Admin request for provisioning a customer employee account batch.
 *
 * <p>Each employee carries its own role codes ({@link EmployeeAccountRow#getRoles()}).
 * There is no type→role mapping table: this platform-generic service does not
 * know any vertical's business roles. Callers that need type→role translation
 * (e.g. the Quote/BOM deployment) resolve it on their side before calling.
 */
@Data
public class EmployeeAccountProvisionRequest {

    @Valid
    @NotEmpty(message = "Employees are required")
    private List<EmployeeAccountRow> employees;

    @Size(min = 1, max = 32)
    private String passwordPrefix = "jjzz@";

    @Min(1)
    @Max(12)
    private Integer randomDigitCount = 4;
}
