package com.auraboot.framework.application.database.dialect;

import java.util.Arrays;
import java.util.Set;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * PostgreSQL dialect implementation.
 * <p>
 * Encapsulates PG-specific SQL: JSONB operators, {@code ::} type cast, ILIKE,
 * ON CONFLICT ... DO UPDATE, gen_random_uuid().
 */
public class PostgresDialect implements DatabaseDialect {

    @Override
    public DatabaseType getType() {
        return DatabaseType.POSTGRESQL;
    }

    // ── JSON ─────────────────────────────────────────────────────────

    @Override
    public String jsonExtractText(String column, String key) {
        return column + " ->> '" + key + "'";
    }

    @Override
    public String jsonContains(String column, String key, String value) {
        return column + " @> '{\"" + key + "\":\"" + value + "\"}'::jsonb";
    }

    // ── Type cast ────────────────────────────────────────────────────

    @Override
    public String typeCast(String expression, String targetType) {
        return expression + "::" + mapType(targetType);
    }

    // ── String ───────────────────────────────────────────────────────

    @Override
    public String caseInsensitiveLike(String column, String pattern) {
        return column + " ILIKE " + pattern;
    }

    // ── Upsert ───────────────────────────────────────────────────────

    @Override
    public String upsertSql(String table, String[] columns, String[] conflictKeys) {
        String colList = String.join(", ", columns);
        String placeholders = Arrays.stream(columns).map(c -> "?").collect(Collectors.joining(", "));
        String conflictList = String.join(", ", conflictKeys);

        Set<String> keySet = Set.of(conflictKeys);
        String updateList = Arrays.stream(columns)
                .filter(c -> !keySet.contains(c))
                .map(c -> c + " = EXCLUDED." + c)
                .collect(Collectors.joining(", "));

        return "INSERT INTO " + table + " (" + colList + ") VALUES (" + placeholders + ") "
                + "ON CONFLICT (" + conflictList + ") DO UPDATE SET " + updateList;
    }

    // ── UUID ─────────────────────────────────────────────────────────

    @Override
    public String generateUuid() {
        return "gen_random_uuid()";
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
        return "now()";
    }

    // ── Internal helpers ─────────────────────────────────────────────

    /**
     * Normalize type names to PostgreSQL equivalents.
     */
    private String mapType(String targetType) {
        return switch (targetType.toUpperCase(Locale.ROOT)) {
            case "INTEGER", "INT" -> "INTEGER";
            case "BIGINT", "LONG" -> "BIGINT";
            case "TEXT", "VARCHAR", "STRING" -> "TEXT";
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            case "TIMESTAMP", "DATETIME" -> "TIMESTAMP";
            case "NUMERIC", "DECIMAL" -> "NUMERIC";
            default -> targetType.toUpperCase(Locale.ROOT);
        };
    }
}
