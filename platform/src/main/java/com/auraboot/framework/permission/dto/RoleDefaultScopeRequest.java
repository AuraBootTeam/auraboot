package com.auraboot.framework.permission.dto;

/**
 * Request body for setting a role's default DATA-scope tier (permission v2 ② dimension).
 * scopeType is one of all / dept_and_sub / dept / self / none; null clears the default.
 */
public record RoleDefaultScopeRequest(
    String scopeType
) {}
