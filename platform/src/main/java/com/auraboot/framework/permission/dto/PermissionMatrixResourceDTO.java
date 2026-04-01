package com.auraboot.framework.permission.dto;

import java.util.List;

/**
 * Permission Matrix Resource DTO
 *
 * <p>Represents a resource (level=2 permission) within a module.
 */
public record PermissionMatrixResourceDTO(
    String resourceCode,
    String resourceName,
    List<PermissionMatrixActionDTO> actions
) {}
