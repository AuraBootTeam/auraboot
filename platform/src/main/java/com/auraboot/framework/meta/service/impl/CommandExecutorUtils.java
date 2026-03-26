package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.security.SqlSafetyUtils;

import java.util.Map;

/**
 * Shared utility methods for the command execution sub-executors.
 * All methods are package-private static.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
final class CommandExecutorUtils {

    private CommandExecutorUtils() {
        // utility class
    }

    /**
     * Validate that a string is a safe SQL identifier (column/table name).
     * Delegates to centralized {@link SqlSafetyUtils#validateIdentifier}.
     */
    static void validateSqlIdentifier(String identifier, String context) {
        SqlSafetyUtils.validateIdentifier(identifier, context);
    }

    /**
     * Validate that a string is a safe SQL fragment (WHERE clause, expression, etc.).
     * Delegates to centralized {@link SqlSafetyUtils#validateSqlFragment}.
     */
    static void validateSqlFragment(String fragment, String context) {
        try {
            SqlSafetyUtils.validateSqlFragment(fragment);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(context + ": " + e.getMessage(), e);
        }
    }

    /**
     * Build record ID lookup condition. If recordId is numeric, use "id" column;
     * otherwise use "pid" column (for ULID-style identifiers).
     */
    static Map.Entry<String, Object> resolveRecordIdColumn(String recordId) {
        try {
            return Map.entry("id", (Object) Long.parseLong(recordId));
        } catch (NumberFormatException e) {
            return Map.entry("pid", (Object) recordId);
        }
    }
}
