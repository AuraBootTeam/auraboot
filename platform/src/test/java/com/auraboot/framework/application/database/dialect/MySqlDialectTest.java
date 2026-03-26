package com.auraboot.framework.application.database.dialect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MySqlDialect")
class MySqlDialectTest {

    private MySqlDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new MySqlDialect();
    }

    @Test
    void getType_returnsMysql() {
        assertEquals(DatabaseType.MYSQL, dialect.getType());
    }

    // ── JSON operations ──────────────────────────────────────────────

    @Test
    void jsonExtractText_usesJsonExtractFunction() {
        String result = dialect.jsonExtractText("extension", "color");
        assertEquals("JSON_UNQUOTE(JSON_EXTRACT(extension, '$.color'))", result);
    }

    @Test
    void jsonContains_usesJsonContainsFunction() {
        String result = dialect.jsonContains("extension", "type", "admin");
        assertEquals("JSON_CONTAINS(extension, '\"admin\"', '$.type')", result);
    }

    // ── Type cast ────────────────────────────────────────────────────

    @Test
    void typeCast_usesCastFunction() {
        assertEquals("CAST(col AS CHAR)", dialect.typeCast("col", "text"));
        assertEquals("CAST(col AS SIGNED)", dialect.typeCast("col", "int"));
        assertEquals("CAST(col AS SIGNED)", dialect.typeCast("col", "bigint"));
        assertEquals("CAST(col AS UNSIGNED)", dialect.typeCast("col", "boolean"));
        assertEquals("CAST(col AS DATETIME)", dialect.typeCast("col", "timestamp"));
        assertEquals("CAST(col AS JSON)", dialect.typeCast("col", "jsonb"));
    }

    // ── Case-insensitive LIKE ────────────────────────────────────────

    @Test
    void caseInsensitiveLike_usesLowerFunction() {
        String result = dialect.caseInsensitiveLike("name", "'%test%'");
        assertEquals("LOWER(name) LIKE LOWER('%test%')", result);
    }

    // ── Upsert ───────────────────────────────────────────────────────

    @Test
    void upsertSql_usesOnDuplicateKeyUpdate() {
        String result = dialect.upsertSql("ns_user",
                new String[]{"id", "name", "email"},
                new String[]{"id"});

        assertTrue(result.contains("INSERT INTO ns_user (id, name, email)"));
        assertTrue(result.contains("VALUES (?, ?, ?)"));
        assertTrue(result.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(result.contains("name = VALUES(name)"));
        assertTrue(result.contains("email = VALUES(email)"));
        // Conflict key should NOT appear in the UPDATE clause
        assertFalse(result.contains("id = VALUES(id)"));
    }

    @Test
    void upsertSql_multipleConflictKeys() {
        String result = dialect.upsertSql("ns_role_permission",
                new String[]{"role_id", "permission_id", "status"},
                new String[]{"role_id", "permission_id"});

        assertTrue(result.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(result.contains("status = VALUES(status)"));
        assertFalse(result.contains("role_id = VALUES(role_id)"));
        assertFalse(result.contains("permission_id = VALUES(permission_id)"));
    }

    // ── UUID generation ──────────────────────────────────────────────

    @Test
    void generateUuid_returnsMysqlUuidFunction() {
        assertEquals("UUID()", dialect.generateUuid());
    }

    // ── Boolean literal ──────────────────────────────────────────────

    @Test
    void booleanLiteral_returnsTrueOrFalse() {
        assertEquals("true", dialect.booleanLiteral(true));
        assertEquals("false", dialect.booleanLiteral(false));
    }

    // ── Pagination ───────────────────────────────────────────────────

    @Test
    void paginationClause_returnsLimitOffset() {
        assertEquals("LIMIT 10 OFFSET 20", dialect.paginationClause(10, 20));
    }

    // ── Current timestamp ────────────────────────────────────────────

    @Test
    void currentTimestamp_returnsMysqlNow() {
        assertEquals("NOW()", dialect.currentTimestamp());
    }
}
