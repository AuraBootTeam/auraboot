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
    @Test void observesAllTablesAtOneReadOnlySnapshotAcrossConcurrentDdl() throws Exception {
        assertEquals("crm-release-foundation", required("AURA_RUNTIME_NAME"));
        String database = required("POSTGRES_DB");
        assertEquals("auracrm_" + required("AURA_WORKSPACE_SLOT"), database);
        var source = new DriverManagerDataSource("jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database,
                required("DATABASE_USERNAME"), required("DATABASE_PASSWORD"));
        var admin = new JdbcTemplate(source);
        String schema = "schema_snapshot_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            admin.execute("CREATE TABLE " + schema + ".first_table (amount integer NOT NULL)");
            admin.execute("CREATE TABLE " + schema + ".second_table (amount integer NOT NULL)");
            var column = new SchemaContractInspector.Column("amount", "integer", false, null, "", "");
            var contracts = java.util.Map.of(
                    "a", new SchemaContractInspector.Contract(schema, "first_table", List.of(column), List.of()),
                    "b", new SchemaContractInspector.Contract(schema, "second_table", List.of(column), List.of()));
            var observerJdbc = new JdbcTemplate(source);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var observer = new SchemaContractInspector(observerJdbc, new ObjectMapper()) {
                @Override public Observation inspect(Contract contract) {
                    assertEquals("on", observerJdbc.queryForObject("SHOW transaction_read_only", String.class));
                    assertEquals("repeatable read", observerJdbc.queryForObject("SHOW transaction_isolation", String.class));
                    var result = super.inspect(contract);
                    if (calls.incrementAndGet() == 1) {
                        // A different connection commits DDL between the two observations.
                        try (var connection = source.getConnection(); var ddl = connection.createStatement()) {
                            assertTrue(connection.getAutoCommit());
                            ddl.execute("ALTER TABLE " + schema + ".second_table ALTER COLUMN amount DROP NOT NULL");
                        } catch (java.sql.SQLException failure) {
                            throw new AssertionError("Independent DDL fixture failed", failure);
                        }
                    }
                    return result;
                }
            };
            var before = observer.inspectAll(contracts);
            assertTrue(before.satisfied());
            assertEquals(2, calls.get());
            assertFalse(before.catalogSnapshot().isBlank());
            assertEquals(List.of("a", "b"), List.copyOf(before.observations().keySet()));
            assertThrows(UnsupportedOperationException.class, () -> before.observations().clear());
            var after = new SchemaContractInspector(observerJdbc, new ObjectMapper()).inspectAll(contracts);
            assertFalse(after.satisfied());
            assertTrue(after.observations().get("a").satisfied());
            assertEquals(List.of("schema-column-mismatch:amount"), after.observations().get("b").findings());
            assertThrows(IllegalArgumentException.class, () -> observer.inspectAll(java.util.Map.of()));
            var writeProbe = new SchemaContractInspector(observerJdbc, new ObjectMapper()) {
                @Override public Observation inspect(Contract contract) {
                    observerJdbc.update("INSERT INTO " + schema + ".first_table(amount) VALUES (1)");
                    return super.inspect(contract);
                }
            };
            assertThrows(org.springframework.dao.DataAccessException.class, () -> writeProbe.inspectAll(contracts));
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM " + schema + ".first_table", Integer.class));
        } finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    @Test void bindsCatalogObservationToExactRegisteredContractBytes() throws Exception {
        assertEquals("crm-release-foundation", required("AURA_RUNTIME_NAME"));
        String database = required("POSTGRES_DB");
        assertEquals("auracrm_" + required("AURA_WORKSPACE_SLOT"), database);
        String url = "jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database;
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, required("DATABASE_USERNAME"), required("DATABASE_PASSWORD")));
        String schema = "registered_schema_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=" + schema,
                    required("DATABASE_USERNAME"), required("DATABASE_PASSWORD")));
            try (var sql = getClass().getResourceAsStream("/db/migration/core/V20260926010000__immutable_platform_release_registry.sql")) {
                assertNotNull(sql); jdbc.execute(new String(sql.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            jdbc.execute("CREATE TABLE sample (amount integer NOT NULL)");
            var mapper = new ObjectMapper();
            var table = new SchemaContractInspector.Contract(schema, "sample",
                    List.of(new SchemaContractInspector.Column("amount", "integer", false, null, "", "")), List.of());
            var document = new RegisteredSchemaContractInspector.ContractDocument(1, "sample-schema", "v1", java.util.Map.of("sample", table));
            byte[] bytes = mapper.writeValueAsBytes(document);
            var platformService = new PlatformReleaseRegistrationService(jdbc, mapper);
            var inspector = new RegisteredSchemaContractInspector(jdbc, mapper, new SchemaContractInspector(jdbc, mapper));
            var platform = registerSchemaContract(platformService, bytes, "1.0.0");
            var observed = inspector.inspect(platform.releaseId(), platform.digest(), "schema-contract", bytes);
            assertEquals(platform.digest(), observed.platformReleaseDigest());
            assertEquals("sample-schema", observed.key()); assertEquals("v1", observed.contract());
            assertEquals(hashBytes(bytes), observed.artifactDigest()); assertTrue(observed.catalog().satisfied());
            assertThrows(IllegalArgumentException.class, () -> inspector.inspect(platform.releaseId(), platform.digest(), "absent", bytes));
            assertThrows(IllegalStateException.class, () -> inspector.inspect(platform.releaseId(), "sha256:" + "0".repeat(64), "schema-contract", bytes));
            var changed = java.util.Arrays.copyOf(bytes, bytes.length + 1); changed[changed.length - 1] = ' ';
            assertThrows(IllegalArgumentException.class, () -> inspector.inspect(platform.releaseId(), platform.digest(), "schema-contract", changed));
            jdbc.execute("ALTER TABLE sample ALTER COLUMN amount DROP NOT NULL");
            var drift = inspector.inspect(platform.releaseId(), platform.digest(), "schema-contract", bytes);
            assertFalse(drift.catalog().satisfied());
            assertEquals(observed.artifactDigest(), drift.artifactDigest());
            assertEquals(List.of("schema-column-mismatch:amount"), drift.catalog().observations().get("sample").findings());
            String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            int version = 2;
            for (String invalid : List.of(raw.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                    raw.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"unknown\":true"),
                    raw.replace("\"nullable\":false,", ""), raw.replace("\"key\":\"sample-schema\"", "\"key\":42"))) {
                assertNotEquals(raw, invalid);
                byte[] invalidBytes = invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var bad = registerSchemaContract(platformService, invalidBytes, version++ + ".0.0");
                assertThrows(IllegalArgumentException.class, () -> inspector.inspect(bad.releaseId(), bad.digest(), "schema-contract", invalidBytes));
            }
        } finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    private static PlatformReleaseRegistrationService.Registration registerSchemaContract(
            PlatformReleaseRegistrationService service, byte[] bytes, String version) throws Exception {
        var contracts = new ObjectMapper().createObjectNode();
        contracts.putArray("runtime").add("v1"); contracts.putArray("pluginApi").add("v1"); contracts.putArray("dslSchema").add(1);
        return service.register("schema-platform", new PlatformReleaseRegistrationService.Content(version, "sha256:" + "a".repeat(64), contracts,
                List.of(new PlatformReleaseRegistrationService.Artifact("config", "schema-contract", version, "artifact:schema-contract/" + version,
                        hashBytes(bytes), new PlatformReleaseRegistrationService.Source("core", "a".repeat(40))))), "test:schema");
    }
    private static String hashBytes(byte[] bytes) throws Exception {
        return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
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
