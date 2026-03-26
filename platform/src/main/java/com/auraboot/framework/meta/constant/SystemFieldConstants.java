package com.auraboot.framework.meta.constant;

import java.util.Set;

/**
 * Canonical definitions for system / infrastructure field codes.
 * <p>
 * Every mt_* table created by DDL contains a fixed set of
 * infrastructure columns. This class centralises the knowledge so that
 * services no longer duplicate their own private sets.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public final class SystemFieldConstants {

    private SystemFieldConstants() {
        // utility
    }

    /** Table name prefix for dynamic entity tables. Single source of truth for all mt_* references. */
    public static final String DYNAMIC_TABLE_PREFIX = "mt_";

    /**
     * Generate the table name for a given model code.
     */
    public static String generateTableName(String modelCode) {
        return DYNAMIC_TABLE_PREFIX + modelCode.toLowerCase();
    }

    /** DDL infrastructure columns for mt_* */
    public static final Set<String> ALL_INFRASTRUCTURE = Set.of(
        "id", "pid", "created_at", "created_by", "updated_at", "updated_by", "tenant_id");

    /** Columns transparent to query - not modeled as MetaField, skip code-to-column mapping */
    public static final Set<String> QUERY_TRANSPARENT = Set.of(
        "tenant_id", "created_at", "created_by", "updated_at", "updated_by");

    /** Pre-seeded fields auto-bound on model creation */
    public static final Set<String> AUTO_BIND = Set.of("id", "pid", "created_at", "updated_at");

    /** Soft-delete markers (only ab_* system tables) */
    public static final Set<String> SOFT_DELETE_MARKERS = Set.of("deleted", "deleted_flag");

    /** All system fields for validation (infrastructure + version) */
    public static final Set<String> VALIDATION_SYSTEM = Set.of(
        "id", "pid", "created_at", "created_by", "updated_at", "updated_by", "tenant_id", "version");

    /**
     * Check if a field code is a system field (infrastructure + soft-delete + version).
     */
    public static boolean isSystemField(String code) {
        return code != null && (ALL_INFRASTRUCTURE.contains(code)
            || SOFT_DELETE_MARKERS.contains(code) || "version".equals(code));
    }
}
