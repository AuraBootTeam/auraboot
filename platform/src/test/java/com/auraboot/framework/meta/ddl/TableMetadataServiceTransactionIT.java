package com.auraboot.framework.meta.ddl;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL service/it regression; uses the caller's isolated TEST_DATABASE_* target. */
class TableMetadataServiceTransactionIT {
    private DriverManagerDataSource dataSource() {
        String url = System.getenv("TEST_DATABASE_URL");
        assertNotNull(url, "TEST_DATABASE_URL must identify the managed test database");
        assertTrue(url.startsWith("jdbc:postgresql:"), "This regression requires PostgreSQL");
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(url);
        source.setUsername(System.getenv("TEST_DATABASE_USERNAME"));
        source.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
        Properties properties = new Properties();
        properties.setProperty("options", "-c statement_timeout=1500");
        source.setConnectionProperties(properties);
        return source;
    }

    @Test
    void metadataReadsSeeUncommittedDdlAndDoNotCloseTheTransactionConnection() {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TableMetadataService metadata = new TableMetadataService(source, new DdlDialectProvider(source));
        String table = "e2e_meta_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String index = "idx_" + table + "_value_tenant_unique";
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            jdbc.execute("CREATE TABLE " + table + " (tenant_id BIGINT, value VARCHAR(20) NOT NULL UNIQUE)");
            jdbc.execute("CREATE UNIQUE INDEX " + index + " ON " + table + " (tenant_id, value)");
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN extra TEXT");
            assertTrue(metadata.tableExists(table));
            assertTrue(metadata.columnExists(table, "extra"));
            assertEquals("VARCHAR(20)", metadata.getColumnTypeDefinition(table, "value"));
            assertFalse(metadata.isColumnNullable(table, "value"));
            assertTrue(metadata.indexExists(table, index));
            assertTrue(metadata.hasGeneratedSingleColumnUniqueConstraint(table, "value"));
            assertTrue(metadata.hasGeneratedTenantUniqueIndex(table, "value"));
            jdbc.update("INSERT INTO " + table + " (tenant_id, value, extra) VALUES (?, ?, ?)", 1L, "same connection", "visible");
            assertEquals("visible", jdbc.queryForObject("SELECT extra FROM " + table, String.class));
            status.setRollbackOnly();
        });
        assertFalse(metadata.tableExists(table), "Rollback must remove the transaction's DDL");
    }

    @Test
    void metadataReadsDoNotWaitOnTheirOwnDdlLockForAnExistingTable() {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TableMetadataService metadata = new TableMetadataService(source, new DdlDialectProvider(source));
        String table = "e2e_lock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.execute("CREATE TABLE " + table + " (value TEXT)");
        try {
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN extra INTEGER");
                assertTrue(metadata.columnExists(table, "value"));
                assertTrue(metadata.columnExists(table, "extra"));
                assertEquals("INTEGER", metadata.getColumnTypeDefinition(table, "extra"));
                assertTrue(metadata.isColumnNullable(table, "extra"));
                status.setRollbackOnly();
            });
            assertFalse(metadata.columnExists(table, "extra"), "Rollback must preserve the original schema");
            assertTrue(metadata.columnExists(table, "value"));
        } finally {
            jdbc.execute("DROP TABLE " + table);
        }
    }
}
