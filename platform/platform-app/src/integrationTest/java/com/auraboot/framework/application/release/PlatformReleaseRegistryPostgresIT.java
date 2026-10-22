package com.auraboot.framework.application.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlatformReleaseRegistryPostgresIT {
    private static final String ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final String NEXT_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAW";
    private static final String PIN = "sha256:" + "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void registersExactBytesAndRejectsInvalidOrMutablePlatformIdentity() throws Exception {
        assertEquals("crm-release-foundation", required("AURA_RUNTIME_NAME"));
        String database = required("POSTGRES_DB");
        assertEquals("auracrm_" + required("AURA_WORKSPACE_SLOT"), database);
        String url = "jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database;
        var admin = jdbc(url);
        String schema = "platform_registry_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var db = jdbc(url + "?currentSchema=" + schema);
            try (var sql = getClass().getResourceAsStream("/db/migration/core/V20260926010000__immutable_platform_release_registry.sql")) {
                assertNotNull(sql);
                db.execute(new String(sql.readAllBytes(), StandardCharsets.UTF_8));
            }
            String original = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest(ID, "1.0.0"));
            insert(db, ID, "1.0.0", original, hash(original));
            assertEquals(original, db.queryForObject("SELECT manifest_text FROM ab_platform_release_registry", String.class));
            assertEquals(hash(original), db.queryForObject("SELECT digest FROM ab_platform_release_registry", String.class));
            assertEquals("operator", db.queryForObject("SELECT registered_by FROM ab_platform_release_registry", String.class));
            for (String sql : List.of("UPDATE ab_platform_release_registry SET registered_by='other'",
                    "DELETE FROM ab_platform_release_registry", "TRUNCATE ab_platform_release_registry")) {
                assertThrows(DataAccessException.class, () -> db.execute(sql));
            }
            String reusedVersion = manifest(NEXT_ID, "1.0.0").toString();
            assertThrows(DataAccessException.class, () -> insert(db, NEXT_ID, "1.0.0", reusedVersion, hash(reusedVersion)));
            String next = manifest(NEXT_ID, "2.0.0").toString();
            assertThrows(DataAccessException.class, () -> insert(db, NEXT_ID, "2.0.0", next, PIN));
            assertThrows(DataAccessException.class, () -> insert(db, NEXT_ID, "3.0.0", next, hash(next)));
            for (String scenario : List.of("unknown", "empty-artifacts", "duplicate-artifact", "bad-source",
                    "snapshot", "missing-contract", "duplicate-contract", "invalid-schema", "numeric-artifact")) {
                var invalid = manifest(NEXT_ID, "2.0.0");
                var artifact = (ObjectNode) invalid.path("artifacts").get(0);
                switch (scenario) {
                    case "unknown" -> invalid.put("published", true);
                    case "empty-artifacts" -> invalid.putArray("artifacts");
                    case "duplicate-artifact" -> invalid.withArray("artifacts").add(artifact.deepCopy());
                    case "bad-source" -> ((ObjectNode) artifact.path("source")).put("commit", "main");
                    case "snapshot" -> artifact.put("version", "1.0-SNAPSHOT");
                    case "missing-contract" -> ((ObjectNode) invalid.path("platformContracts")).remove("pluginApi");
                    case "duplicate-contract" -> ((ObjectNode) invalid.path("platformContracts")).withArray("runtime").add("runtime-v1");
                    case "invalid-schema" -> ((ObjectNode) invalid.path("platformContracts")).putArray("dslSchema").add(1.5);
                    case "numeric-artifact" -> artifact.put("id", 123);
                    default -> fail(scenario);
                }
                String raw = invalid.toString();
                assertThrows(DataAccessException.class, () -> insert(db, NEXT_ID, "2.0.0", raw, hash(raw)), scenario);
            }
            String duplicateJson = next.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
            assertNotEquals(next, duplicateJson);
            assertThrows(DataAccessException.class, () -> insert(db, NEXT_ID, "2.0.0", duplicateJson, hash(duplicateJson)));
            assertEquals(1, db.queryForObject("SELECT count(*) FROM ab_platform_release_registry", Integer.class));
            insert(db, NEXT_ID, "2.0.0", next, hash(next));
            assertEquals(2, db.queryForObject("SELECT count(*) FROM ab_platform_release_registry", Integer.class));
            try (var sql = getClass().getResourceAsStream("/db/migration/core/V20260926020000__platform_artifact_coordinate_guard.sql")) {
                assertNotNull(sql);
                // The migration lock and historical check are one transaction, as with Flyway.
                var tx = new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
                String migration = new String(sql.readAllBytes(), StandardCharsets.UTF_8);
                tx.executeWithoutResult(status -> db.execute(migration));
            }
            verifyService(db);
            verifyCoordinates(db);
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void migrationRejectsHistoricalCoordinateConflictWithoutRewritingContent() throws Exception {
        assertEquals("crm-release-foundation", required("AURA_RUNTIME_NAME"));
        String database = required("POSTGRES_DB");
        assertEquals("auracrm_" + required("AURA_WORKSPACE_SLOT"), database);
        String url = "jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database;
        var admin = jdbc(url);
        String schema = "platform_conflict_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var db = jdbc(url + "?currentSchema=" + schema);
            try (var sql = PlatformReleaseRegistryPostgresIT.class.getResourceAsStream("/db/migration/core/V20260926010000__immutable_platform_release_registry.sql")) {
                assertNotNull(sql); db.execute(new String(sql.readAllBytes(), StandardCharsets.UTF_8));
            }
            String one = manifest(ID, "1.0.0").toString();
            var changed = manifest(NEXT_ID, "2.0.0");
            ((ObjectNode) changed.path("artifacts").get(0)).put("version", "1.0.0").put("digest", "sha256:" + "b".repeat(64));
            String two = changed.toString();
            insert(db, ID, "1.0.0", one, hash(one)); insert(db, NEXT_ID, "2.0.0", two, hash(two));
            try (var sql = PlatformReleaseRegistryPostgresIT.class.getResourceAsStream("/db/migration/core/V20260926020000__platform_artifact_coordinate_guard.sql")) {
                assertNotNull(sql);
                String migration = new String(sql.readAllBytes(), StandardCharsets.UTF_8);
                var tx = new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
                assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> db.execute(migration)));
            }
            assertEquals(List.of(one, two), db.queryForList("SELECT manifest_text FROM ab_platform_release_registry ORDER BY version", String.class));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid='ab_platform_release_registry'::regclass AND tgname='trg_platform_registry_coordinates'", Integer.class));
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static void verifyService(JdbcTemplate db) throws Exception {
        var service = new PlatformReleaseRegistrationService(db, JSON);
        var contracts = (ObjectNode) manifest(ID, "1.0.0").path("platformContracts");
        var source = new PlatformReleaseRegistrationService.Source("core", "a".repeat(40));
        var runtime = new PlatformReleaseRegistrationService.Artifact("runtime", "core", "1.0.0", "artifact:core", PIN, source);
        var definition = new PlatformReleaseRegistrationService.Artifact("config", "core-definition", "1.0.0", "artifact:definition", PIN, source);
        var content = new PlatformReleaseRegistrationService.Content("1.0.0", PIN, contracts, List.of(runtime, definition));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return service.register("service-platform", content, "test:first"); });
            var second = workers.submit(() -> { start.await(); return service.register("service-platform", content, "test:second"); });
            start.countDown();
            var registered = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(registered, second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(hash(registered.manifestText()), registered.digest());
            assertTrue(List.of("test:first", "test:second").contains(registered.registeredBy()));
            contracts.putArray("runtime").add("runtime-v2").add("runtime-v1");
            var reordered = new PlatformReleaseRegistrationService.Content("1.0.0", PIN, contracts, List.of(definition, runtime));
            assertEquals(registered, service.register("service-platform", reordered, "test:retry"));
            var changed = new PlatformReleaseRegistrationService.Content("1.0.0", "sha256:" + "b".repeat(64), contracts, List.of(runtime, definition));
            assertThrows(IllegalStateException.class, () -> service.register("service-platform", changed, "test:retry"));
        }
        var duplicate = new PlatformReleaseRegistrationService.Content("2.0.0", PIN, contracts, List.of(runtime, runtime));
        assertThrows(IllegalArgumentException.class, () -> service.register("service-platform", duplicate, "test:invalid"));
        assertThrows(IllegalArgumentException.class, () -> service.register("service-platform", content, "forged"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM ab_platform_release_registry WHERE platform_code='service-platform'", Integer.class));
    }

    private static void verifyCoordinates(JdbcTemplate db) throws Exception {
        var service = new PlatformReleaseRegistrationService(db, JSON);
        var contracts = (ObjectNode) manifest(ID, "1.0.0").path("platformContracts");
        var source = new PlatformReleaseRegistrationService.Source("core", "a".repeat(40));
        var original = new PlatformReleaseRegistrationService.Artifact("runtime", "core", "1.0.0", "artifact:core", PIN, source);
        var changed = new PlatformReleaseRegistrationService.Artifact("runtime", "core", "1.0.0", "artifact:core", "sha256:" + "b".repeat(64), source);
        service.register("coordinate-test", new PlatformReleaseRegistrationService.Content("1.0.0", PIN, contracts, List.of(original)), "test:coordinate");
        service.register("coordinate-test", new PlatformReleaseRegistrationService.Content("2.0.0", PIN, contracts, List.of(original)), "test:reuse");
        assertThrows(PlatformReleaseRegistrationService.ArtifactCoordinateConflictException.class, () -> service.register("coordinate-test",
                new PlatformReleaseRegistrationService.Content("3.0.0", PIN, contracts, List.of(changed)), "test:conflict"));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM ab_platform_release_registry WHERE platform_code='coordinate-test'", Integer.class));
        service.register("another-platform", new PlatformReleaseRegistrationService.Content("1.0.0", PIN, contracts, List.of(changed)), "test:scope");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(status -> service.register("snapshot-test",
                new PlatformReleaseRegistrationService.Content("1.0.0", PIN, contracts, List.of(original)), "test:snapshot")));

        try (var first = db.getDataSource().getConnection();
             var second = db.getDataSource().getConnection();
             var workers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            first.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
            int secondPid;
            try (var statement = second.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertTrue(rows.next()); secondPid = rows.getInt(1);
            }
            var one = manifest(com.auraboot.framework.common.util.UlidGenerator.generate(), "1.0.0").put("platform", "coordinate-race");
            var two = manifest(com.auraboot.framework.common.util.UlidGenerator.generate(), "2.0.0").put("platform", "coordinate-race");
            ((ObjectNode) two.path("artifacts").get(0)).put("version", "1.0.0").put("digest", "sha256:" + "b".repeat(64));
            try {
                insertConnection(first, one);
                var waiting = workers.submit(() -> assertThrows(java.sql.SQLException.class, () -> insertConnection(second, two)));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    blocked = Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND locktype='advisory' AND NOT granted)", Boolean.class, secondPid));
                    if (blocked) break;
                    Thread.sleep(10);
                }
                assertTrue(blocked, "Second registration must wait on the platform lock");
                first.commit();
                assertEquals("23514", waiting.get(5, java.util.concurrent.TimeUnit.SECONDS).getSQLState());
                assertEquals(1, db.queryForObject("SELECT count(*) FROM ab_platform_release_registry WHERE platform_code='coordinate-race'", Integer.class));
            } finally {
                first.rollback();
            }
        }
    }

    private static void insertConnection(java.sql.Connection connection, ObjectNode manifest) throws Exception {
        String raw = manifest.toString();
        try (var statement = connection.prepareStatement("INSERT INTO ab_platform_release_registry(release_id,platform_code,version,source_lock_identity,manifest_text,digest,registered_by) VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, manifest.path("releaseId").asText());
            statement.setString(2, manifest.path("platform").asText());
            statement.setString(3, manifest.path("version").asText());
            statement.setString(4, PIN); statement.setString(5, raw); statement.setString(6, hash(raw));
            statement.setString(7, "test:concurrent"); statement.executeUpdate();
        }
    }

    private static ObjectNode manifest(String id, String version) {
        var body = JSON.createObjectNode().put("schemaVersion", 1).put("releaseId", id)
                .put("platform", "auraboot").put("version", version).put("sourceLockIdentity", PIN);
        var contracts = body.putObject("platformContracts");
        contracts.putArray("runtime").add("runtime-v1").add("runtime-v2");
        contracts.putArray("pluginApi").add("api-v1");
        contracts.putArray("dslSchema").add(4);
        var artifact = body.putArray("artifacts").addObject().put("type", "runtime").put("id", "core")
                .put("version", version).put("digest", PIN).put("uri", "artifact:core");
        artifact.putObject("source").put("repository", "core").put("commit", "a".repeat(40));
        return body;
    }

    private static void insert(JdbcTemplate db, String id, String version, String body, String digest) {
        db.update("INSERT INTO ab_platform_release_registry(release_id,platform_code,version,source_lock_identity,manifest_text,digest,registered_by) VALUES (?,'auraboot',?,?,?,?, 'operator')",
                id, version, PIN, body, digest);
    }

    private static String hash(String value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, required("DATABASE_USERNAME"), required("DATABASE_PASSWORD")));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name);
        assertFalse(value.isBlank(), name);
        return value;
    }
}
