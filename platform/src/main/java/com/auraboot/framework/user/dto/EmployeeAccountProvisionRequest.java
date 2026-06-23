package com.auraboot.framework.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Admin request for provisioning a customer employee account batch.
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

    /**
     * Optional override by employee type, e.g. {"销售":["custom_sales"]}.
     * Defaults cover 管理员/销售/采购/工程 and common English aliases.
     */
    private Map<String, List<String>> roleMapping;
}
