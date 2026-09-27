package com.auraboot.framework.meta.ddl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real registry migration and database invariants; not release admission or tenant binding. */
class ApplicationReleaseRegistryPostgresIT {
    @Test void registrationProjectsComponentsAtomicallyAndRejectsMutation() throws Exception {
        assertEquals("crm-release-foundation", required("AURA_RUNTIME_NAME"));
        String database = required("POSTGRES_DB");
        assertEquals("auracrm_" + required("AURA_WORKSPACE_SLOT"), database);
        String url = "jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database;
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, required("DATABASE_USERNAME"), required("DATABASE_PASSWORD")));
        String schema = "release_registry_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=" + schema,
                    required("DATABASE_USERNAME"), required("DATABASE_PASSWORD")));
            try (var migration = getClass().getResourceAsStream("/db/migration/core/V20260925030000__immutable_application_release_registry.sql")) {
                assertNotNull(migration);
                jdbc.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO ab_application(code,name,next_release_sequence) VALUES ('bad-sequence','Bad',2)"));
            Long app = jdbc.queryForObject("INSERT INTO ab_application(code,name) VALUES ('aura-edu','Aura EDU') RETURNING id", Long.class);
            String first = "01K00000000000000000000001";
            ObjectNode body = manifest(first, 1);
            insert(jdbc, app, body, digest(body.toString()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_application_release_component", Integer.class));
            assertEquals("edu-core", jdbc.queryForObject("SELECT component_key FROM ab_application_release_component", String.class));
            assertEquals(2L, jdbc.queryForObject("SELECT next_release_sequence FROM ab_application", Long.class));
            assertThrows(RuntimeException.class, () -> jdbc.execute("TRUNCATE ab_application_release CASCADE"));
            assertThrows(RuntimeException.class, () -> jdbc.execute("TRUNCATE ab_application_release_component"));
            assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE ab_application_release SET compatibility_epoch=2"));
            assertThrows(RuntimeException.class, () -> jdbc.update("DELETE FROM ab_application_release"));
            assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE ab_application_release_component SET component_version='changed'"));
            assertThrows(RuntimeException.class, () -> jdbc.update("DELETE FROM ab_application_release_component"));
            assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO ab_application_release_component SELECT release_id,'extra',component_type,component_version,component_digest,compatibility_contract FROM ab_application_release_component"));
            assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE ab_application SET next_release_sequence=99"));
            assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE ab_application SET code='changed'"));
            assertThrows(RuntimeException.class, () -> insert(jdbc, app, body, digest(body.toString())));
            ObjectNode wrongDigest = manifest("01K00000000000000000000002", 2);
            assertThrows(RuntimeException.class, () -> insert(jdbc, app, wrongDigest, "sha256:" + "0".repeat(64)));
            ObjectNode wrongApp = manifest("01K00000000000000000000002", 2).put("application", "aura-other");
            assertThrows(RuntimeException.class, () -> insert(jdbc, app, wrongApp, digest(wrongApp.toString())));
            ObjectNode duplicate = manifest("01K00000000000000000000002", 2);
            duplicate.withArray("components").add(duplicate.withArray("components").get(0).deepCopy());
            assertThrows(RuntimeException.class, () -> insert(jdbc, app, duplicate, digest(duplicate.toString())));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_application_release", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_application_release_component", Integer.class));
            assertEquals(2L, jdbc.queryForObject("SELECT next_release_sequence FROM ab_application", Long.class));
            ObjectNode changedCoordinate = manifest("01K00000000000000000000002", 2);
            ((ObjectNode) changedCoordinate.withArray("components").get(0)).put("digest", "sha256:" + "c".repeat(64));
            assertThrows(RuntimeException.class, () -> insert(jdbc, app, changedCoordinate, digest(changedCoordinate.toString())));
            ObjectNode mutable = manifest("01K00000000000000000000002", 2);
            ((ObjectNode) mutable.withArray("components").get(0)).put("version", "latest");
            assertThrows(RuntimeException.class, () -> insert(jdbc, app, mutable, digest(mutable.toString())));
            assertEquals(2L, jdbc.queryForObject("SELECT next_release_sequence FROM ab_application", Long.class));
            ObjectNode second = manifest("01K00000000000000000000002", 2);
            insert(jdbc, app, second, digest(second.toString()));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_application_release", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_application_release_component", Integer.class));
            assertEquals(3L, jdbc.queryForObject("SELECT next_release_sequence FROM ab_application", Long.class));
        } finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
    private static ObjectNode manifest(String id, int sequence) {
        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("schemaVersion", 1).put("application", "aura-edu").put("releaseId", id)
                .put("releaseSequence", sequence).put("compatibilityEpoch", 1)
                .put("sourceLockIdentity", "sha256:" + "a".repeat(64));
        body.putObject("platformCompatibility").put("runtimeContract", "1");
        body.putArray("components").addObject().put("key", "edu-core").put("type", "definition")
                .put("version", "1.0.0").put("digest", "sha256:" + "b".repeat(64));
        return body;
    }
    private static void insert(JdbcTemplate jdbc, Long app, ObjectNode body, String digest) {
        jdbc.update("""
                INSERT INTO ab_application_release(release_id,application_id,release_sequence,compatibility_epoch,
                    source_lock_identity,manifest_text,digest) VALUES (?,?,?,1,?,?,?)
                """, body.get("releaseId").asText(), app, body.get("releaseSequence").asLong(),
                body.get("sourceLockIdentity").asText(), body.toString(), digest);
    }
    private static String digest(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static String required(String name) {
        String value = System.getenv(name); assertNotNull(value, name); assertFalse(value.isBlank(), name); return value;
    }
}
