package com.auraboot.framework.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * One employee row from a customer account list.
 */
@Data
public class EmployeeAccountRow {

    @NotBlank(message = "Employee name is required")
    @Size(max = 64)
    private String name;

    /**
     * Optional business label (销售/采购/工程/…). Metadata only — it is echoed
     * back in the response and does not derive any roles. Roles come from
     * {@link #roles}; this service intentionally holds no type→role knowledge.
     */
    @Size(max = 32)
    private String type;

    /**
     * Role codes to assign to this employee. When empty the account is created
     * with no roles. Every code must already exist in the tenant, otherwise
     * provisioning fails with "Missing tenant roles".
     */
    private List<String> roles;

    @Email(message = "Invalid email format")
    private String email;

    @Size(max = 64)
    private String mobile;
}
