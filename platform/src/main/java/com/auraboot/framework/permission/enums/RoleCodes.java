package com.auraboot.framework.permission.enums;

/**
 * Canonical role code constants.
 *
 * <p>Role codes are lowercase by project convention. Keep this class as the
 * single source of truth for role-code string literals to avoid the magic-string
 * red line (see {@code code-quality.md}).
 */
public final class RoleCodes {

    /** Tenant-scoped administrator. Required for every {@code /api/admin/**} endpoint. */
    public static final String TENANT_ADMIN = "tenant_admin";

    /** Cross-tenant platform administrator (reserved for Phase 2). */
    public static final String PLATFORM_ADMIN = "platform_admin";

    private RoleCodes() {
        // utility
    }
}
