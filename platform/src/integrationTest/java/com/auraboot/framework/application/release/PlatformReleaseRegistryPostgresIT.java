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
            verifyService(db);
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
