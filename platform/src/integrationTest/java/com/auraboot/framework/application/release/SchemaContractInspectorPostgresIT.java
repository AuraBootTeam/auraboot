package com.auraboot.framework.application.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SchemaContractInspectorPostgresIT {
    @Test void observesActualShapeInReadOnlyTransactionAndRejectsDrift() throws Exception {
        assertEquals("crm-release-foundation", required("AURA_RUNTIME_NAME"));
        String database = required("POSTGRES_DB");
        assertEquals("auracrm_" + required("AURA_WORKSPACE_SLOT"), database);
        var source = new DriverManagerDataSource("jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database,
                required("DATABASE_USERNAME"), required("DATABASE_PASSWORD"));
        var admin = new JdbcTemplate(source);
        String schema = "schema_contract_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            admin.execute("CREATE TABLE " + schema + ".sample (amount integer NOT NULL, CONSTRAINT positive CHECK (amount >= 0))");
            var column = new SchemaContractInspector.Column("amount", "integer", false, null, "", "");
            var constraint = new SchemaContractInspector.Constraint("positive", "CHECK ((amount >= 0))");
            var contract = new SchemaContractInspector.Contract(schema, "sample", List.of(column), List.of(constraint));
            assertTrue(observe(source, contract).satisfied());
            admin.execute("ALTER TABLE " + schema + ".sample ADD COLUMN optional_field text");
            assertTrue(observe(source, contract).satisfied());
            admin.execute("ALTER TABLE " + schema + ".sample ALTER COLUMN amount DROP NOT NULL");
            assertEquals(List.of("schema-column-mismatch:amount"), observe(source, contract).findings());
            admin.execute("ALTER TABLE " + schema + ".sample ALTER COLUMN amount SET NOT NULL");
            admin.execute("ALTER TABLE " + schema + ".sample ALTER COLUMN amount SET DEFAULT 10");
            assertFalse(observe(source, contract).satisfied());
            admin.execute("ALTER TABLE " + schema + ".sample ALTER COLUMN amount DROP DEFAULT");
            admin.execute("ALTER TABLE " + schema + ".sample DROP CONSTRAINT positive");
            assertEquals(List.of("schema-constraint-missing:positive"), observe(source, contract).findings());
            admin.execute("ALTER TABLE " + schema + ".sample ADD CONSTRAINT positive CHECK (amount >= 0) NOT VALID");
            assertFalse(observe(source, contract).satisfied());
            admin.execute("ALTER TABLE " + schema + ".sample VALIDATE CONSTRAINT positive");
            assertTrue(observe(source, contract).satisfied());
            admin.execute("ALTER TABLE " + schema + ".sample ALTER COLUMN amount TYPE bigint");
            assertFalse(observe(source, contract).satisfied());
            admin.execute("ALTER TABLE " + schema + ".sample RENAME COLUMN amount TO renamed");
            assertTrue(observe(source, contract).findings().contains("schema-column-missing:amount"));
            assertEquals(List.of("schema-table-missing"), observe(source, new SchemaContractInspector.Contract(schema, "absent", List.of(column), List.of())).findings());
            admin.execute("CREATE VIEW " + schema + ".view_sample AS SELECT renamed AS amount FROM " + schema + ".sample");
            assertEquals(List.of("schema-relation-not-table"), observe(source, new SchemaContractInspector.Contract(schema, "view_sample", List.of(column), List.of())).findings());
            assertThrows(IllegalArgumentException.class, () -> observe(source,
                    new SchemaContractInspector.Contract(schema, "sample", List.of(), List.of())));
        } finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
    private static SchemaContractInspector.Observation observe(DriverManagerDataSource source, SchemaContractInspector.Contract contract) throws Exception {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                assertEquals("on", jdbc.queryForObject("SHOW transaction_read_only", String.class));
                return new SchemaContractInspector(jdbc, new ObjectMapper()).inspect(contract);
            } finally { connection.rollback(); }
        }
    }
    private static String required(String name) {
        String value = System.getenv(name); assertNotNull(value, name); assertFalse(value.isBlank(), name); return value;
    }
}
