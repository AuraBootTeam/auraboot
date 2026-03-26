package com.auraboot.framework.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for admin user provisioning.
 * Creates a user and assigns them to the current tenant with specified roles.
 *
 * @since 7.1.0
 */
@Data
public class UserProvisionRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Display name is required")
    @Size(min = 1, max = 50)
    private String displayName;

    /**
     * Initial password. If null, a temporary password is generated
     * and mustChangePassword is set to true.
     */
    @Size(min = 6, max = 128)
    private String initialPassword;

    /**
     * Role codes to assign (e.g. ["operator", "viewer"]).
     * If empty, the tenant's default role is assigned.
     */
    private List<String> roleCodes;

    /**
     * Whether to send an invite email with credentials.
     * Default: false (for script/API usage).
     */
    private Boolean sendInviteEmail = false;
}
