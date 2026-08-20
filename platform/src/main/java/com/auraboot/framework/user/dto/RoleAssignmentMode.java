package com.auraboot.framework.user.dto;

/**
 * Controls how tenant roles are assigned during administrator provisioning.
 */
public enum RoleAssignmentMode {
    /** Assign the tenant default role when no explicit role codes are provided. */
    DEFAULT,
    /** Assign only the supplied role codes. */
    EXPLICIT,
    /** Create the active tenant member without any explicit business role. */
    NONE
}
