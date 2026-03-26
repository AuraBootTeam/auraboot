package com.auraboot.framework.application.database.dialect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PostgresDialect")
class PostgresDialectTest {

    private PostgresDialect dialect;

    @BeforeEach
    void setUp() {
        dialect = new PostgresDialect();
    }

    @Test
    void getType_returnsPostgresql() {
        assertEquals(DatabaseType.POSTGRESQL, dialect.getType());
    }

    // ── JSON operations ──────────────────────────────────────────────

    @Test
    void jsonExtractText_usesArrowOperator() {
        String result = dialect.jsonExtractText("extension", "color");
        assertEquals("extension ->> 'color'", result);
    }

    @Test
    void jsonContains_usesContainsOperator() {
        String result = dialect.jsonContains("extension", "type", "admin");
        assertEquals("extension @> '{\"type\":\"admin\"}'::jsonb", result);
    }

    // ── Type cast ────────────────────────────────────────────────────

    @Test
    void typeCast_usesDoubleColonSyntax() {
        assertEquals("col::TEXT", dialect.typeCast("col", "text"));
        assertEquals("col::INTEGER", dialect.typeCast("col", "int"));
        assertEquals("col::BIGINT", dialect.typeCast("col", "bigint"));
        assertEquals("col::BOOLEAN", dialect.typeCast("col", "boolean"));
        assertEquals("col::TIMESTAMP", dialect.typeCast("col", "timestamp"));
    }

    // ── Case-insensitive LIKE ────────────────────────────────────────

    @Test
    void caseInsensitiveLike_usesIlike() {
        String result = dialect.caseInsensitiveLike("name", "'%test%'");
        assertEquals("name ILIKE '%test%'", result);
    }

    // ── Upsert ───────────────────────────────────────────────────────

    @Test
    void upsertSql_usesOnConflictDoUpdate() {
        String result = dialect.upsertSql("ns_user",
                new String[]{"id", "name", "email"},
                new String[]{"id"});

        assertTrue(result.contains("INSERT INTO ns_user (id, name, email)"));
        assertTrue(result.contains("VALUES (?, ?, ?)"));
        assertTrue(result.contains("ON CONFLICT (id) DO UPDATE SET"));
        assertTrue(result.contains("name = EXCLUDED.name"));
        assertTrue(result.contains("email = EXCLUDED.email"));
        // Conflict key should NOT appear in the UPDATE SET clause
        assertFalse(result.contains("id = EXCLUDED.id"));
    }

    // ── UUID generation ──────────────────────────────────────────────

    @Test
    void generateUuid_returnsGenRandomUuid() {
        assertEquals("gen_random_uuid()", dialect.generateUuid());
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
    void currentTimestamp_returnsNow() {
        assertEquals("now()", dialect.currentTimestamp());
    }
}
