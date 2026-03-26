package com.auraboot.framework.rbac.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for copying role permissions.
 */
public record CopyPermissionsRequest(
        @NotNull(message = "targetRoleId must not be null")
        Long targetRoleId
) {
}
