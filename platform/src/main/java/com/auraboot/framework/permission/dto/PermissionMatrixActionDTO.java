package com.auraboot.framework.permission.dto;

import java.util.Map;

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
    boolean supported,
    String scopeType,       // current scope for this role+action (null if not configured)
    String mergeStrategy,   // MAX or MIN (null if not configured)
    String policySchema,    // JSON string of policy_schema, null if no schema defined
    Map<String, Object> policyValues  // current policy values for this role+permission
) {}
