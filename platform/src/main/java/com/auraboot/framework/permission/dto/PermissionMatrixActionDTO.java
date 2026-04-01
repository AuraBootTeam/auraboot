package com.auraboot.framework.permission.dto;

/**
 * Permission Matrix Action DTO
 *
 * <p>Represents a single action (leaf permission) in the matrix.
 */
public record PermissionMatrixActionDTO(
    Long permissionId,
    String permissionPid,
    String code,
    String action,
    String label,
    boolean granted,
    boolean supported
) {}
