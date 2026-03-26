package com.auraboot.framework.application.database.dialect;

import java.util.Arrays;
import java.util.Set;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * MySQL 8.0+ dialect implementation.
 * <p>
 * Translates PostgreSQL-style constructs to MySQL equivalents:
 * <ul>
 *   <li>JSONB → JSON (native JSON support since MySQL 5.7)</li>
 *   <li>{@code ::type} → {@code CAST(x AS type)}</li>
 *   <li>ILIKE → {@code LOWER(col) LIKE LOWER(pattern)}</li>
 *   <li>ON CONFLICT DO UPDATE → ON DUPLICATE KEY UPDATE</li>
 *   <li>gen_random_uuid() → UUID()</li>
 * </ul>
 */
public class MySqlDialect implements DatabaseDialect {

    @Override
    public DatabaseType getType() {
        return DatabaseType.MYSQL;
    }

    // ── JSON ─────────────────────────────────────────────────────────

    @Override
    public String jsonExtractText(String column, String key) {
        return "JSON_UNQUOTE(JSON_EXTRACT(" + column + ", '$." + key + "'))";
    }

    @Override
    public String jsonContains(String column, String key, String value) {
        return "JSON_CONTAINS(" + column + ", '\"" + value + "\"', '$." + key + "')";
    }

    // ── Type cast ────────────────────────────────────────────────────

    @Override
    public String typeCast(String expression, String targetType) {
        return "CAST(" + expression + " AS " + mapType(targetType) + ")";
    }

    // ── String ───────────────────────────────────────────────────────

    @Override
    public String caseInsensitiveLike(String column, String pattern) {
        // MySQL default collation (utf8mb4_general_ci) is case-insensitive,
        // but we use LOWER() for explicit correctness with any collation.
        return "LOWER(" + column + ") LIKE LOWER(" + pattern + ")";
    }

    // ── Upsert ───────────────────────────────────────────────────────

    @Override
    public String upsertSql(String table, String[] columns, String[] conflictKeys) {
        String colList = String.join(", ", columns);
        String placeholders = Arrays.stream(columns).map(c -> "?").collect(Collectors.joining(", "));

        Set<String> keySet = Set.of(conflictKeys);
        String updateList = Arrays.stream(columns)
                .filter(c -> !keySet.contains(c))
                .map(c -> c + " = VALUES(" + c + ")")
                .collect(Collectors.joining(", "));

        return "INSERT INTO " + table + " (" + colList + ") VALUES (" + placeholders + ") "
                + "ON DUPLICATE KEY UPDATE " + updateList;
    }

    // ── UUID ─────────────────────────────────────────────────────────

    @Override
    public String generateUuid() {
        return "UUID()";
    }

    // ── Boolean ──────────────────────────────────────────────────────

    @Override
    public String booleanLiteral(boolean value) {
        return value ? "true" : "false";
    }

    // ── Pagination ───────────────────────────────────────────────────

    @Override
    public String paginationClause(int limit, int offset) {
        return "LIMIT " + limit + " OFFSET " + offset;
    }

    // ── Timestamp ────────────────────────────────────────────────────

    @Override
    public String currentTimestamp() {
        return "NOW()";
    }

    // ── Internal helpers ─────────────────────────────────────────────

    /**
     * Normalize type names to MySQL CAST-compatible types.
     * MySQL CAST supports: BINARY, CHAR, DATE, DATETIME, DECIMAL, JSON,
     * SIGNED [INTEGER], TIME, UNSIGNED [INTEGER], YEAR.
     */
    private String mapType(String targetType) {
        return switch (targetType.toUpperCase(Locale.ROOT)) {
            case "INTEGER", "INT", "BIGINT", "LONG" -> "SIGNED";
            case "TEXT", "VARCHAR", "STRING" -> "CHAR";
            case "BOOLEAN", "BOOL" -> "UNSIGNED";  // MySQL has no CAST to BOOLEAN
            case "TIMESTAMP", "DATETIME" -> "DATETIME";
            case "NUMERIC", "DECIMAL" -> "DECIMAL";
            case "JSON", "JSONB" -> "JSON";
            default -> targetType.toUpperCase(Locale.ROOT);
        };
    }
}
