package com.auraboot.framework.user.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response DTO for admin user provisioning.
 *
 * @since 7.1.0
 */
@Data
@Builder
public class UserProvisionResponse {
    private Long userId;
    private String userPid;
    private String email;
    private String displayName;
    private Long tenantId;
    private List<String> assignedRoles;
    private boolean mustChangePassword;
    private String temporaryPassword; // only returned when no initialPassword was provided
}
