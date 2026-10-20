package com.auraboot.framework.application.database.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DialectAutoConfiguration")
class DialectAutoConfigurationTest {

    // ── Explicit configuration ───────────────────────────────────────

    @Test
    void explicitPostgresql_createsPostgresDialect() {
        DialectAutoConfiguration config = makeConfig("postgresql", "");
        DatabaseDialect dialect = config.databaseDialect();
        assertEquals(DatabaseType.POSTGRESQL, dialect.getType());
        assertInstanceOf(PostgresDialect.class, dialect);
    }

    @Test
    void explicitPg_createsPostgresDialect() {
        DialectAutoConfiguration config = makeConfig("pg", "");
        DatabaseDialect dialect = config.databaseDialect();
        assertEquals(DatabaseType.POSTGRESQL, dialect.getType());
    }

    @Test
    void explicitPostgres_createsPostgresDialect() {
        DialectAutoConfiguration config = makeConfig("postgres", "");
        DatabaseDialect dialect = config.databaseDialect();
        assertEquals(DatabaseType.POSTGRESQL, dialect.getType());
    }

    @Test
    void explicitInvalid_throwsException() {
        DialectAutoConfiguration config = makeConfig("oracle", "");
        assertThrows(IllegalArgumentException.class, config::databaseDialect);
    }

    // ── Auto-detection from URL ──────────────────────────────────────

    @Test
    void postgresUrl_autoDetectsPostgresql() {
        DialectAutoConfiguration config = makeConfig("",
                "jdbc:postgresql://localhost:5432/mydb?charSet=UTF8");
        DatabaseDialect dialect = config.databaseDialect();
        assertEquals(DatabaseType.POSTGRESQL, dialect.getType());
    }

    // ── Default ──────────────────────────────────────────────────────

    @Test
    void noConfig_defaultsToPostgresql() {
        DialectAutoConfiguration config = makeConfig("", "");
        DatabaseDialect dialect = config.databaseDialect();
        assertEquals(DatabaseType.POSTGRESQL, dialect.getType());
    }

    // ── Explicit rejects unknown dialects ────────────────────────────

    @Test
    void explicitMysql_rejectedAsUnsupported() {
        DialectAutoConfiguration config = makeConfig("mysql",
                "jdbc:postgresql://localhost:5432/mydb");
        assertThrows(IllegalArgumentException.class, config::databaseDialect);
    }

    // ── Helper ───────────────────────────────────────────────────────

    private DialectAutoConfiguration makeConfig(String explicitDialect, String datasourceUrl) {
        DialectAutoConfiguration config = new DialectAutoConfiguration();
        ReflectionTestUtils.setField(config, "explicitDialect", explicitDialect);
        ReflectionTestUtils.setField(config, "datasourceUrl", datasourceUrl);
        return config;
    }
}
