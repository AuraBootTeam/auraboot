package com.auraboot.framework.application.database.dialect;

/**
 * Abstraction layer for database-specific SQL syntax.
 * <p>
 * Each method returns a SQL fragment that is valid for the target database.
 * Implementation: PostgreSQL (the platform database).
 */
public interface DatabaseDialect {

    /**
     * Return the database type this dialect targets.
     */
    DatabaseType getType();

    // ── JSON operations ──────────────────────────────────────────────

    /**
     * Extract a text value from a JSON/JSONB column.
     * <p>
     * PostgreSQL: {@code column ->> 'key'}
     */
    String jsonExtractText(String column, String key);

    /**
     * Test whether a JSON/JSONB column contains a key-value pair.
     * <p>
     * PostgreSQL: {@code column @> '{"key":"value"}'::jsonb}
     */
    String jsonContains(String column, String key, String value);

    // ── Type casting ─────────────────────────────────────────────────

    /**
     * Cast an expression to a target SQL type.
     * <p>
     * PostgreSQL: {@code expression::targetType}
     *
     * @param expression SQL expression
     * @param targetType target type name (e.g. TEXT, INTEGER, BIGINT, BOOLEAN, TIMESTAMP)
     */
    String typeCast(String expression, String targetType);

    // ── String operations ────────────────────────────────────────────

    /**
     * Case-insensitive LIKE condition.
     * <p>
     * PostgreSQL: {@code column ILIKE pattern}
     *
     * @param column  column name
     * @param pattern LIKE pattern (caller must include % wildcards)
     */
    String caseInsensitiveLike(String column, String pattern);

    // ── Upsert ───────────────────────────────────────────────────────

    /**
     * Build an upsert (insert-or-update) statement.
     * <p>
     * PostgreSQL: {@code INSERT INTO ... ON CONFLICT (keys) DO UPDATE SET ...}
     *
     * @param table        table name
     * @param columns      all columns to insert
     * @param conflictKeys columns that form the unique/primary constraint
     */
    String upsertSql(String table, String[] columns, String[] conflictKeys);

    // ── UUID generation ──────────────────────────────────────────────

    /**
     * Database-native UUID generation function.
     * <p>
     * PostgreSQL: {@code gen_random_uuid()}
     */
    String generateUuid();

    // ── Boolean literal ──────────────────────────────────────────────

    /**
     * Boolean literal for use in SQL.
     * <p>
     * PostgreSQL: {@code TRUE / FALSE}
     */
    String booleanLiteral(boolean value);

    // ── Pagination ───────────────────────────────────────────────────

    /**
     * Pagination clause.
     * <p>
     ** PostgreSQL supports {@code LIMIT n OFFSET m},
     * but this method exists for future extensibility.
     */
    String paginationClause(int limit, int offset);

    // ── Current timestamp ────────────────────────────────────────────

    /**
     * Expression that returns the current timestamp.
     * <p>
     * PostgreSQL: {@code now()}
     */
    String currentTimestamp();
}
