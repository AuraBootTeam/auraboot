package com.auraboot.framework.meta.security;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Centralized SQL safety utilities.
 * <p>
 * Consolidates SQL identifier validation and SQL fragment/statement safety checks
 * that were previously duplicated across DynamicSqlProvider, DataPermissionEngineImpl,
 * ReportTemplateServiceImpl, QueryBuilderServiceImpl, and CommandExecutorUtils.
 * <p>
 * Three levels of validation:
 * <ul>
 *   <li>{@link #validateIdentifier} — table names, column names (whitelist regex)</li>
 *   <li>{@link #validateSqlFragment} — WHERE clauses, ORDER BY, expressions (blacklist keywords + structural checks)</li>
 *   <li>{@link #validateSelectOnlySql} — complete SELECT statements (must start with SELECT, no DML/DDL)</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 5.2.0
 */
public final class SqlSafetyUtils {

    private SqlSafetyUtils() {
    }

    // ==================== Identifier Validation ====================

    /**
     * Valid SQL identifier: starts with letter or underscore, followed by
     * alphanumerics/underscores, max 128 characters.
     */
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,127}$");

    /**
     * Validate a SQL identifier (table name, column name, field code).
     *
     * @param identifier the identifier to validate
     * @param context    description for error messages (e.g., "table name", "column name")
     * @throws IllegalArgumentException if the identifier is invalid
     */
    public static void validateIdentifier(String identifier, String context) {
        if (identifier == null || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Invalid SQL identifier for " + context + ": " + identifier);
        }
    }

    /**
     * Validate and return a SQL identifier for call sites that need to make the
     * trusted value explicit before composing SQL with table or column names.
     */
    public static String requireIdentifier(String identifier, String context) {
        validateIdentifier(identifier, context);
        return identifier;
    }

    /**
     * Check if a string is a valid SQL identifier without throwing.
     */
    public static boolean isValidIdentifier(String identifier) {
        return identifier != null && IDENTIFIER_PATTERN.matcher(identifier).matches();
    }

    // ==================== SQL Fragment Validation ====================

    /**
     * All SQL keywords that should NEVER appear in a WHERE clause, ORDER BY,
     * or expression fragment provided by user/config input.
     */
    private static final Set<String> DANGEROUS_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE",
            "ALTER", "CREATE", "EXEC", "EXECUTE", "CALL",
            "GRANT", "REVOKE", "UNION",
            "INTO OUTFILE", "LOAD_FILE"
    );

    /**
     * Pattern to detect word boundaries for single-word keywords.
     * Multi-word keywords (INTO OUTFILE, LOAD_FILE) are checked via contains().
     */
    private static final Pattern WORD_BOUNDARY_TEMPLATE =
            Pattern.compile("\\b(%s)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Pre-compiled pattern for all single-word dangerous keywords.
     */
    private static final Pattern DANGEROUS_KEYWORD_PATTERN;

    static {
        String singleWords = DANGEROUS_KEYWORDS.stream()
                .filter(k -> !k.contains(" ") && !k.contains("_"))
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
        DANGEROUS_KEYWORD_PATTERN = Pattern.compile(
                "\\b(" + singleWords + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    /**
     * Validate a SQL fragment (WHERE clause, ORDER BY, filter expression).
     * <p>
     * Rejects fragments containing:
     * <ul>
     *   <li>Semicolons (statement separator — prevents multi-statement injection)</li>
     *   <li>SQL comments ({@code --}, {@code /*})</li>
     *   <li>Any dangerous SQL keyword (SELECT, INSERT, UPDATE, DELETE, DROP, etc.)</li>
     * </ul>
     *
     * @param fragment the SQL fragment to validate
     * @throws IllegalArgumentException if the fragment contains dangerous patterns
     */
    public static void validateSqlFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return;
        }

        // 1. Reject semicolons (prevents multi-statement injection)
        if (fragment.contains(";")) {
            throw new IllegalArgumentException(
                    "SQL fragment must not contain semicolons: potential statement injection");
        }

        // 2. Reject SQL comments
        if (fragment.contains("--") || fragment.contains("/*") || fragment.contains("*/")) {
            throw new IllegalArgumentException(
                    "SQL fragment must not contain comment markers (-- or /* */)");
        }

        // 3. Reject unbalanced or nested parentheses (blocks subqueries like "(SELECT ...)")
        int depth = 0;
        for (char c : fragment.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth > 1) {
                throw new IllegalArgumentException(
                        "SQL fragment must not contain nested parentheses: potential subquery injection");
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException(
                    "SQL fragment has unbalanced parentheses");
        }

        // 4. Reject dangerous keywords
        if (DANGEROUS_KEYWORD_PATTERN.matcher(fragment).find()) {
            throw new IllegalArgumentException(
                    "SQL fragment contains forbidden keyword: " + fragment);
        }

        // 5. Check multi-word keywords
        String upper = fragment.toUpperCase();
        if (upper.contains("INTO OUTFILE") || upper.contains("LOAD_FILE")) {
            throw new IllegalArgumentException(
                    "SQL fragment contains forbidden keyword: " + fragment);
        }
    }

    /**
     * Check if a SQL expression contains dangerous keywords (non-throwing version).
     * Suitable for filter expressions where rejection returns a safe default.
     *
     * @return true if the expression contains dangerous SQL patterns
     */
    public static boolean containsDangerousPatterns(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        if (sql.contains(";") || sql.contains("--") || sql.contains("/*") || sql.contains("*/")) {
            return true;
        }
        // Check for nested parentheses (subquery indicator)
        int depth = 0;
        for (char c : sql.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth > 1) return true;
        }
        if (depth != 0) return true;

        if (DANGEROUS_KEYWORD_PATTERN.matcher(sql).find()) {
            return true;
        }
        String upper = sql.toUpperCase();
        return upper.contains("INTO OUTFILE") || upper.contains("LOAD_FILE");
    }

    // ==================== Complete SQL Statement Validation ====================

    /**
     * Validate a complete SQL statement that must be a read-only SELECT.
     * <p>
     * Checks:
     * <ul>
     *   <li>Must start with SELECT (after trimming)</li>
     *   <li>No semicolons (prevents multi-statement)</li>
     *   <li>No SQL comments</li>
     *   <li>No DML/DDL keywords: INSERT, UPDATE, DELETE, DROP, TRUNCATE, ALTER, CREATE</li>
     *   <li>No privilege keywords: GRANT, REVOKE, EXEC, EXECUTE, CALL</li>
     *   <li>No UNION (prevents UNION-based data exfiltration)</li>
     * </ul>
     *
     * @param sql the complete SQL statement to validate
     * @throws IllegalArgumentException if the SQL is not a safe SELECT statement
     */
    public static void validateSelectOnlySql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL statement cannot be empty");
        }

        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();

        // Must start with SELECT
        if (!upper.startsWith("SELECT")) {
            throw new IllegalArgumentException("SQL must be a SELECT statement");
        }

        // No semicolons
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException(
                    "SELECT SQL must not contain semicolons");
        }

        // No comments
        if (trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("*/")) {
            throw new IllegalArgumentException(
                    "SELECT SQL must not contain comment markers");
        }

        // Check for forbidden keywords (excluding SELECT itself which is allowed)
        Set<String> forbiddenInSelect = Set.of(
                "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE",
                "ALTER", "CREATE", "EXEC", "EXECUTE", "CALL",
                "GRANT", "REVOKE", "UNION"
        );

        for (String keyword : forbiddenInSelect) {
            Pattern p = Pattern.compile("\\b" + keyword + "\\b", Pattern.CASE_INSENSITIVE);
            if (p.matcher(trimmed).find()) {
                throw new IllegalArgumentException(
                        "SELECT SQL must not contain forbidden keyword: " + keyword);
            }
        }

        // Check multi-word dangerous patterns
        if (upper.contains("INTO OUTFILE") || upper.contains("LOAD_FILE")) {
            throw new IllegalArgumentException(
                    "SELECT SQL must not contain forbidden pattern: file operations");
        }
    }

    // ==================== LIMIT Validation ====================

    /** Default maximum limit for query results. */
    public static final int DEFAULT_MAX_LIMIT = 10_000;

    /**
     * Clamp a LIMIT value to a safe maximum.
     *
     * @param limit    the requested limit
     * @param maxLimit the maximum allowed limit
     * @return the clamped limit value (at least 1, at most maxLimit)
     */
    public static int clampLimit(int limit, int maxLimit) {
        return Math.max(1, Math.min(limit, maxLimit));
    }
}
