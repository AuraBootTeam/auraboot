package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises generated DDL against an explicitly selected, owned PostgreSQL database. */
class PostgresDdlCodeTypesIT {

    private final PostgresDdlDialect dialect = new PostgresDdlDialect();

    @Test
    void legacyPluginFileIdentifiersRoundTripAsStrings() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE ddl_file_code (code " + type("file", 32)
                    + " DEFAULT " + dialect.formatDefaultValue("01M40R49GX8JJAH5KGNKGP5FJX", "file") + ")");
            statement.executeUpdate("INSERT INTO ddl_file_code DEFAULT VALUES");
            try (var result = statement.executeQuery("SELECT code, pg_typeof(code)::text FROM ddl_file_code")) {
                assertTrue(result.next());
                assertEquals("01M40R49GX8JJAH5KGNKGP5FJX", result.getString(1));
                assertEquals("character varying", result.getString(2));
            }
        }
    }

    @Test
    void enumAndReferenceDefaultsRoundTripThroughPostgres() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            String enumType = type("enum", 32);
            String referenceType = type("reference", 32);
            statement.execute("CREATE TEMP TABLE ddl_codes (status " + enumType + " DEFAULT "
                    + dialect.formatDefaultValue("owner's-code", "enum") + ", owner " + referenceType
                    + " DEFAULT " + dialect.formatDefaultValue("01M40R49GX8JJAH5KGNKGP5FJX", "reference") + ")");
            statement.executeUpdate("INSERT INTO ddl_codes DEFAULT VALUES");
            try (var result = statement.executeQuery("SELECT status, owner FROM ddl_codes")) {
                assertTrue(result.next());
                assertEquals("owner's-code", result.getString(1));
                assertEquals("01M40R49GX8JJAH5KGNKGP5FJX", result.getString(2));
            }
        }
    }

    @Test
    void numericLookingEnumDefaultKeepsItsStringCode() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE ddl_numeric_code (code " + type("enum", 8)
                    + " DEFAULT " + dialect.formatDefaultValue(0, "enum") + ")");
            statement.executeUpdate("INSERT INTO ddl_numeric_code DEFAULT VALUES");
            try (var result = statement.executeQuery("SELECT code, pg_typeof(code)::text FROM ddl_numeric_code")) {
                assertTrue(result.next());
                assertEquals("0", result.getString(1));
                assertEquals("character varying", result.getString(2));
            }
        }
    }

    @Test
    void postgresEnforcesTheConfiguredReferenceLength() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE ddl_reference_length (code " + type("reference", 8) + ")");
            statement.executeUpdate("INSERT INTO ddl_reference_length VALUES ('12345678')");
            SQLException error = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("INSERT INTO ddl_reference_length VALUES ('123456789')"));
            assertEquals("22001", error.getSQLState());
            try (var result = statement.executeQuery("SELECT count(*) FROM ddl_reference_length")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void aCharacterColumnBecomesJsonbOnlyThroughTheGeneratedUsingClause() throws Exception {
        // Core 8a9b5b765 made multiple-file fields jsonb. Columns created before that are character
        // types, and PostgreSQL has no assignment cast from a character type to jsonb (seen on a
        // product database on 2026-11-23: three columns had to be cast by hand).
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE ddl_using (id integer primary key, evidence character varying(255), payload jsonb)");
            statement.executeUpdate("INSERT INTO ddl_using VALUES (1, '', NULL), (2, '[\"a\",\"b\"]', '{}'), (3, NULL, '{\"k\":1}')");

            SQLException withoutUsing = assertThrows(SQLException.class,
                    () -> statement.execute("ALTER TABLE ddl_using ALTER COLUMN evidence TYPE jsonb"));
            assertEquals("42804", withoutUsing.getSQLState());

            statement.execute("ALTER TABLE ddl_using ALTER COLUMN evidence TYPE jsonb"
                    + dialect.getAlterColumnTypeUsing("evidence", "character varying(255)", "jsonb"));
            statement.execute("ALTER TABLE ddl_using ALTER COLUMN payload TYPE text"
                    + dialect.getAlterColumnTypeUsing("payload", "jsonb", "text"));

            try (var result = statement.executeQuery(
                    "SELECT evidence::text, payload, pg_typeof(evidence)::text, pg_typeof(payload)::text FROM ddl_using ORDER BY id")) {
                assertTrue(result.next());
                assertEquals(null, result.getString(1), "the empty string became NULL");
                assertEquals(null, result.getString(2));
                assertEquals("jsonb", result.getString(3));
                assertEquals("text", result.getString(4));
                assertTrue(result.next());
                assertEquals("[\"a\", \"b\"]", result.getString(1));
                assertEquals("{}", result.getString(2));
                assertTrue(result.next());
                assertEquals(null, result.getString(1));
                assertEquals("{\"k\": 1}", result.getString(2));
            }
        }
    }

    @Test
    void aCharacterColumnWithSomethingThatIsNotJsonStopsTheConversion() throws Exception {
        // Nothing is guessed or dropped: one value that is not JSON makes the whole statement fail
        // and the column keeps its type.
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE ddl_using_bad (id integer primary key, evidence character varying(255))");
            statement.executeUpdate("INSERT INTO ddl_using_bad VALUES (1, 'not json')");
            SQLException invalid = assertThrows(SQLException.class, () -> statement.execute("ALTER TABLE ddl_using_bad ALTER COLUMN evidence TYPE jsonb"
                    + dialect.getAlterColumnTypeUsing("evidence", "character varying(255)", "jsonb")));
            assertEquals("22P02", invalid.getSQLState());
            try (var result = statement.executeQuery("SELECT pg_typeof(evidence)::text, evidence FROM ddl_using_bad")) {
                assertTrue(result.next());
                assertEquals("character varying", result.getString(1));
                assertEquals("not json", result.getString(2));
            }
        }
    }

    private String type(String type, int length) {
        return dialect.mapDataType(FieldDefinition.builder()
                .code("code").dataType(type).maxLength(length).build());
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(required("AURA_DDL_TEST_JDBC_URL"),
                required("AURA_DDL_TEST_USER"), required("AURA_DDL_TEST_PASSWORD"));
    }

    private static String required(String name) {
        return Objects.requireNonNull(System.getenv(name), "Required owned database configuration: " + name);
    }
}
