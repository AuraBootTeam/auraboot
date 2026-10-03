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
