package com.auraboot.framework.application.database.dialect;

/**
 * Abstraction layer for database-specific SQL syntax.
 * <p>
 * Each method returns a SQL fragment that is valid for the target database.
 * Implementations exist for PostgreSQL and MySQL.
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
     * MySQL:      {@code JSON_UNQUOTE(JSON_EXTRACT(column, '$.key'))}
     */
    String jsonExtractText(String column, String key);

    /**
     * Test whether a JSON/JSONB column contains a key-value pair.
     * <p>
     * PostgreSQL: {@code column @> '{"key":"value"}'::jsonb}
     * MySQL:      {@code JSON_CONTAINS(column, '"value"', '$.key')}
     */
    String jsonContains(String column, String key, String value);

    // ── Type casting ─────────────────────────────────────────────────

    /**
     * Cast an expression to a target SQL type.
     * <p>
     * PostgreSQL: {@code expression::targetType}
     * MySQL:      {@code CAST(expression AS targetType)}
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
     * MySQL:      {@code LOWER(column) LIKE LOWER(pattern)}
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
     * MySQL:      {@code INSERT INTO ... ON DUPLICATE KEY UPDATE ...}
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
     * MySQL:      {@code UUID()}
     */
    String generateUuid();

    // ── Boolean literal ──────────────────────────────────────────────

    /**
     * Boolean literal for use in SQL.
     * <p>
     * PostgreSQL: {@code TRUE / FALSE}
     * MySQL:      {@code TRUE / FALSE} (or 1/0 for older versions)
     */
    String booleanLiteral(boolean value);

    // ── Pagination ───────────────────────────────────────────────────

    /**
     * Pagination clause.
     * <p>
     * Both PostgreSQL and MySQL support {@code LIMIT n OFFSET m},
     * but this method exists for future extensibility.
     */
    String paginationClause(int limit, int offset);

    // ── Current timestamp ────────────────────────────────────────────

    /**
     * Expression that returns the current timestamp.
     * <p>
     * PostgreSQL: {@code now()}
     * MySQL:      {@code NOW()}
     */
    String currentTimestamp();
}
