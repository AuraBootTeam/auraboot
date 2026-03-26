package com.auraboot.framework.rbac.constant;

import java.util.Set;

/**
 * Role constants — reserved codes and scope rules.
 */
public final class RoleConstants {

    private RoleConstants() {}

    /** Platform-level role code. Must only exist in the System Tenant with scope_type=global. */
    public static final String PLATFORM_ADMIN = "platform_admin";

    /**
     * Role codes that are reserved for the platform level (System Tenant only).
     * These codes MUST NOT be created in business tenants.
     */
    public static final Set<String> PLATFORM_ONLY_CODES = Set.of(PLATFORM_ADMIN);

    /**
     * Check whether a role code is reserved for platform level only.
     */
    public static boolean isPlatformOnly(String code) {
        return code != null && PLATFORM_ONLY_CODES.contains(code);
    }
}
