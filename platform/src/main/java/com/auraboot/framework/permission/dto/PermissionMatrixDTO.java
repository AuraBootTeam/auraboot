package com.auraboot.framework.permission.dto;

import java.util.List;

/**
 * Permission Matrix DTO
 *
 * <p>Top-level container for the permission matrix view.
 * Contains a list of modules, each with resources and actions.
 */
public record PermissionMatrixDTO(List<PermissionMatrixModuleDTO> modules) {}
