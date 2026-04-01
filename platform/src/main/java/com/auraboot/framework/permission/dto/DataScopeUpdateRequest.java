package com.auraboot.framework.permission.dto;

/**
 * Request body for updating a data scope entry on a role+resource+action combination.
 */
public record DataScopeUpdateRequest(
    String resourceCode,
    String actionCode,
    String scopeType,
    String mergeStrategy
) {}
