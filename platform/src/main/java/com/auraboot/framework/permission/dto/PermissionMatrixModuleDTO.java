package com.auraboot.framework.permission.dto;

import java.util.List;

/**
 * Permission Matrix Module DTO
 *
 * <p>Represents a top-level module (level=1 permission) in the matrix.
 */
public record PermissionMatrixModuleDTO(
    String moduleCode,
    String moduleName,
    List<PermissionMatrixResourceDTO> resources
) {}
