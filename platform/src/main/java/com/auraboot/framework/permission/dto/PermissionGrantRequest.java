package com.auraboot.framework.permission.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Permission Grant Request
 *
 * <p>Used for batch updating role permissions in the matrix view.
 */
public record PermissionGrantRequest(
    @NotNull Long permissionId,
    @NotNull Boolean granted
) {}
