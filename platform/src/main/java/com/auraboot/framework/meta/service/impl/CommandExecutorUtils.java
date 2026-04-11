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
     * Check whether a Java value is type-compatible with the declared DSL field dataType.
     * Used to prevent passing wrong-typed handler results into SQL updates.
     *
     * @param value    the Java object to check (must not be null)
     * @param dataType the DSL field dataType string (e.g. "datetime", "integer", "text")
     * @return true if the value's Java type is compatible with the dataType
     */
    static boolean isTypeCompatible(Object value, String dataType) {
        return switch (dataType.toLowerCase()) {
            case "datetime", "date", "timestamp" ->
                value instanceof java.util.Date
                    || value instanceof java.time.temporal.Temporal
                    || value instanceof java.sql.Timestamp
                    || value instanceof java.sql.Date;
            case "integer", "int" -> value instanceof Number;
            case "decimal", "float", "double", "money" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> true;  // text, enum, json, reference — accept any
        };
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
