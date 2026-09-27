package com.auraboot.framework.application.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.application.release.ApplicationReleaseRegistrationService.Component;
import com.auraboot.framework.application.release.ApplicationReleaseRegistrationService.Content;
import com.auraboot.framework.application.release.ApplicationReleaseRegistrationService.Registration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring transaction proxy and simultaneous PostgreSQL registration requests. */
class ApplicationReleaseRegistrationServicePostgresIT {
    @Configuration
    @EnableTransactionManagement
    static class Transactions {}

    @Test void concurrentRetriesShareOneRegistrationAndConflictsRollBack() throws Exception {
        assertEquals("crm-release-foundation", required("AURA_RUNTIME_NAME"));
        String database = required("POSTGRES_DB");
        assertEquals("auracrm_" + required("AURA_WORKSPACE_SLOT"), database);
        String url = "jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database;
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, required("DATABASE_USERNAME"), required("DATABASE_PASSWORD")));
        String schema = "registration_service_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(url + "?currentSchema=" + schema,
                    required("DATABASE_USERNAME"), required("DATABASE_PASSWORD"));
            var jdbc = new JdbcTemplate(source);
            for (String name : List.of("V20260925030000__immutable_application_release_registry.sql",
                    "V20260925040000__application_release_registration_keys.sql",
                    "V20260925040100__application_release_registration_actors.sql",
                    "V20260925040200__application_creation_actors.sql",
                    "V20260926010000__immutable_platform_release_registry.sql",
                    "V20260926020000__platform_artifact_coordinate_guard.sql")) {
                try (var migration = getClass().getResourceAsStream("/db/migration/core/" + name)) {
                    assertNotNull(migration);
                    String sql = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
                    new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                            .executeWithoutResult(status -> jdbc.execute(sql));
                }
            }
            jdbc.update("INSERT INTO ab_application(code,name) VALUES ('historical-app','Historical')");
            try (var migration = getClass().getResourceAsStream("/db/migration/core/V20260925040300__require_new_registration_provenance.sql")) {
                assertNotNull(migration); jdbc.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertNull(jdbc.queryForObject("SELECT created_by FROM ab_application WHERE code='historical-app'", String.class));
            jdbc.update("DELETE FROM ab_application WHERE code='historical-app'");
            var missingCreator = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                    "INSERT INTO ab_application(code,name) VALUES ('raw-app','Raw')"));
            assertTrue(missingCreator.getMostSpecificCause().getMessage().contains("Application creator is required"));
            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(Transactions.class);
                context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
                context.registerBean(ApplicationReleaseRegistrationService.class,
                        () -> new ApplicationReleaseRegistrationService(jdbc, new ObjectMapper()));
                context.refresh();
                var service = context.getBean(ApplicationReleaseRegistrationService.class);
                var application = service.createApplication("aura-edu", "Aura EDU", "test:creator");
                assertEquals("test:creator", application.createdBy());
                assertEquals(application, service.createApplication("aura-edu", "Aura EDU", "test:retry"));
                assertThrows(IllegalStateException.class, () -> service.createApplication("aura-edu", "Other", "test:retry"));
                assertThrows(IllegalArgumentException.class, () -> service.createApplication("aura-other", "Other", null));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_application", Integer.class));
                assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                        "UPDATE ab_application SET created_by='test:forged' WHERE code='aura-edu'"));
                Registration first = service.register("aura-edu", "build-1", content("b"), "test:publisher");
                assertTrue(com.auraboot.framework.common.util.UlidGenerator.isValid(first.releaseId()));
                assertEquals(1, first.sequence());
                var rawManifest = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(first.manifestText());
                rawManifest.put("releaseId", com.auraboot.framework.common.util.UlidGenerator.generate()).put("releaseSequence", 2);
                String rawText = rawManifest.toString();
                for (int missing = 0; missing < 3; missing++) {
                    String key = missing == 0 ? null : "raw-attempt";
                    String requestDigest = missing == 1 ? null : "sha256:" + "e".repeat(64);
                    String actor = missing == 2 ? null : "test:raw";
                    var failure = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                            "INSERT INTO ab_application_release(release_id,application_id,release_sequence,compatibility_epoch,source_lock_identity,manifest_text,digest,registration_key,registration_request_digest,registered_by) VALUES (?,?,2,1,?,?, 'sha256:' || encode(sha256(convert_to(?, 'UTF8')), 'hex'),?,?,?)",
                            rawManifest.get("releaseId").asText(), application.id(), "sha256:" + "a".repeat(64), rawText, rawText, key, requestDigest, actor));
                    assertTrue(failure.getMostSpecificCause().getMessage().contains("registration key, request digest and actor are required"));
                }
                assertEquals(2L, jdbc.queryForObject("SELECT next_release_sequence FROM ab_application", Long.class));
                assertEquals("test:publisher", first.registeredBy());
                assertEquals("test:publisher", jdbc.queryForObject(
                        "SELECT registered_by FROM ab_application_release WHERE release_id=?", String.class, first.releaseId()));
                assertEquals(first, service.register("aura-edu", "build-1", content("b"), "test:retry-worker"));
                assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                        "UPDATE ab_application_release SET registered_by='test:forged' WHERE release_id=?", first.releaseId()));
                assertThrows(IllegalArgumentException.class, () -> service.register("aura-edu", "no-actor", content("b"), null));
                assertThrows(IllegalArgumentException.class, () -> service.register("aura-edu", "bad-actor", content("b"), "anonymous"));
                assertEquals(first, service.register("aura-edu", "build-1", content("b"), "test:publisher"));
                assertThrows(IllegalStateException.class, () -> service.register("aura-edu", "build-1", content("c"), "test:publisher"));
                // The database rejects a changed digest for the same component coordinate.
                assertThrows(RuntimeException.class, () -> service.register("aura-edu", "changed-coordinate", content("c"), "test:publisher"));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_application_release", Integer.class));
                assertEquals(2L, jdbc.queryForObject("SELECT next_release_sequence FROM ab_application", Long.class));
                var same = race(service, "same-request", "same-request");
                assertEquals(same.get(0), same.get(1));
                assertEquals(2, same.getFirst().sequence());
                var distinct = race(service, "build-3", "build-4");
                assertNotEquals(distinct.get(0).releaseId(), distinct.get(1).releaseId());
                assertEquals(List.of(3L, 4L), distinct.stream().map(Registration::sequence).sorted().toList());
                assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM ab_application_release", Integer.class));
                assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM ab_application_release_component", Integer.class));
                assertEquals(5L, jdbc.queryForObject("SELECT next_release_sequence FROM ab_application", Long.class));
                assertEquals(4, jdbc.queryForObject("SELECT count(DISTINCT registration_key) FROM ab_application_release", Integer.class));
                var mapper = new ObjectMapper();
                Component core = content("b").components().getFirst();
                Component extra = new Component("edu-extra", "definition", "1.0.0", "sha256:" + "d".repeat(64), null);
                Content ordered = new Content(1, "1.0", "sha256:" + "a".repeat(64),
                        mapper.createObjectNode().put("runtimeContract", "1").put("dsl", 4), List.of(core, extra));
                Content reordered = new Content(1, "1.0", "sha256:" + "a".repeat(64),
                        mapper.createObjectNode().put("dsl", 4).put("runtimeContract", "1"), List.of(extra, core));
                Registration normalized = service.register("aura-edu", "normalized-input", ordered, "test:publisher");
                assertEquals(normalized, service.register("aura-edu", "normalized-input", reordered, "test:retry-worker"));
                assertEquals(5, normalized.sequence());
                assertThrows(IllegalArgumentException.class, () -> service.register("missing-app", "build-1", content("b"), "test:publisher"));
                assertThrows(IllegalArgumentException.class, () -> service.register("aura-edu", "", content("b"), "test:publisher"));
                assertEquals(6L, jdbc.queryForObject("SELECT next_release_sequence FROM ab_application", Long.class));
                verifyHttpBoundary(service, jdbc);
                verifyDatabaseRoles(source, schema);
                verifyPrivateLoginPool(source, schema);
                verifyRegisteredDefinitionInspection(service, jdbc);
                verifyBindingStorage(service, jdbc, source, application.id(), first.releaseId(),
                        distinct.get(0).releaseId(), distinct.get(1).releaseId());
            }
        } finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
    private void verifyBindingStorage(ApplicationReleaseRegistrationService service, JdbcTemplate jdbc,
                                      DriverManagerDataSource source, long applicationId,
                                      String first, String second, String third) throws Exception {
        // This isolated registry schema supplies only the tenant identity FK boundary, not tenant bootstrap.
        jdbc.execute("CREATE TABLE ab_tenant(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO ab_tenant VALUES (710),(711)");
        try (var migration = getClass().getResourceAsStream("/db/migration/core/V20260926030000__tenant_application_binding_history.sql")) {
            assertNotNull(migration);
            new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> jdbc.execute(new String(readBytes(migration), StandardCharsets.UTF_8)));
        }
        String insert = "INSERT INTO ab_tenant_application_binding(tenant_id,application_id,current_release_id) VALUES (?,?,?)";
        jdbc.update(insert, 710L, applicationId, first);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history", Integer.class));
        assertEquals("shadow", jdbc.queryForObject("SELECT status FROM ab_tenant_application_binding", String.class));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(insert, 710L, applicationId, first));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(insert, 999L, applicationId, first));
        service.createApplication("binding-other", "Other binding app", "test:creator");
        var other = service.register("binding-other", "build-1", content("b"), "test:publisher");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(insert, 711L, applicationId, other.releaseId()));
        String cas = "UPDATE ab_tenant_application_binding SET current_release_id=?,binding_version=binding_version+1 WHERE tenant_id=710 AND application_id=? AND binding_version=?";
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var left = workers.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return jdbc.update(cas, second, applicationId, 1L); });
            var right = workers.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return jdbc.update(cas, third, applicationId, 1L); });
            start.countDown();
            assertEquals(1, left.get(15, TimeUnit.SECONDS) + right.get(15, TimeUnit.SECONDS));
        }
        assertEquals(2L, jdbc.queryForObject("SELECT binding_version FROM ab_tenant_application_binding", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history", Integer.class));
        assertEquals(first, jdbc.queryForObject("SELECT previous_release_id FROM ab_tenant_application_binding_history WHERE binding_version=2", String.class));
        assertEquals(0, jdbc.update(cas, first, applicationId, 1L));
        var tx = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(status -> {
            assertEquals(1, jdbc.update(cas, first, applicationId, 2L));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history", Integer.class));
            status.setRollbackOnly();
        });
        assertEquals(2L, jdbc.queryForObject("SELECT binding_version FROM ab_tenant_application_binding", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history", Integer.class));
        for (String sql : List.of(
                "UPDATE ab_tenant_application_binding SET binding_version=9",
                "UPDATE ab_tenant_application_binding SET tenant_id=711,binding_version=3",
                "UPDATE ab_tenant_application_binding SET binding_version=3",
                "DELETE FROM ab_tenant_application_binding", "TRUNCATE ab_tenant_application_binding CASCADE",
                "UPDATE ab_tenant_application_binding_history SET database_actor='forged'",
                "DELETE FROM ab_tenant_application_binding_history", "TRUNCATE ab_tenant_application_binding_history",
                "INSERT INTO ab_tenant_application_binding_history SELECT * FROM ab_tenant_application_binding_history")) {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute(sql), sql);
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history", Integer.class));
        verifyShadowStore(jdbc, source, applicationId, first, second, third, other.releaseId());
    }
    private void verifyShadowStore(JdbcTemplate jdbc, DriverManagerDataSource source, long applicationId,
                                   String first, String second, String third, String otherRelease) throws Exception {
        try (var migration = getClass().getResourceAsStream("/db/migration/core/V20260926040000__tenant_binding_operator_audit.sql")) {
            assertNotNull(migration); jdbc.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
        }
        var store = new TenantApplicationShadowBindingStore(jdbc);
        java.util.function.Function<String, String> digest = release -> jdbc.queryForObject(
                "SELECT digest FROM ab_application_release WHERE release_id=?", String.class, release);
        var firstAudit = bindingAudit();
        var initial = store.createShadow(711, applicationId, first, digest.apply(first), firstAudit);
        assertEquals(initial, store.createShadow(711, applicationId, first, digest.apply(first), bindingAudit()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history WHERE tenant_id=711", Integer.class));
        assertEquals(firstAudit.actor(), jdbc.queryForObject("SELECT business_actor FROM ab_tenant_application_binding_history WHERE tenant_id=711", String.class));
        assertEquals(firstAudit.operationId(), jdbc.queryForObject("SELECT operation_id FROM ab_tenant_application_binding_history WHERE tenant_id=711", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history WHERE tenant_id=710 AND business_actor IS NULL", Integer.class));
        var missingAudit = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "UPDATE ab_tenant_application_binding SET current_release_id=?,binding_version=binding_version+1 WHERE tenant_id=710", first));
        assertTrue(missingAudit.getMostSpecificCause().getMessage().contains("requires business actor"));
        assertThrows(IllegalArgumentException.class, () -> store.createShadow(711, applicationId, first, "sha256:" + "0".repeat(64), bindingAudit()));
        assertThrows(IllegalArgumentException.class, () -> store.compareAndSetShadow(initial, otherRelease, digest.apply(otherRelease), bindingAudit()));
        assertThrows(IllegalArgumentException.class, () -> store.compareAndSetShadow(initial, first, digest.apply(first), bindingAudit()));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.function.Function<String, Boolean> change = target -> {
                try { store.compareAndSetShadow(initial, target, digest.apply(target), bindingAudit()); return true; }
                catch (TenantApplicationShadowBindingStore.BindingConflictException conflict) { return false; }
            };
            var left = workers.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return change.apply(second); });
            var right = workers.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return change.apply(third); });
            start.countDown(); assertNotEquals(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        }
        assertEquals(2L, jdbc.queryForObject("SELECT binding_version FROM ab_tenant_application_binding WHERE tenant_id=711", Long.class));
        assertThrows(TenantApplicationShadowBindingStore.BindingConflictException.class,
                () -> store.createShadow(711, applicationId, first, digest.apply(first), bindingAudit()));
        assertThrows(TenantApplicationShadowBindingStore.BindingConflictException.class,
                () -> store.compareAndSetShadow(initial, second, digest.apply(second), bindingAudit()));
        String winner = jdbc.queryForObject("SELECT current_release_id FROM ab_tenant_application_binding WHERE tenant_id=711", String.class);
        var current = new TenantApplicationShadowBindingStore.Binding(711, applicationId, winner, digest.apply(winner), 1, "shadow", 2);
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> store.compareAndSetShadow(current, first, digest.apply(first), firstAudit));
        assertEquals(2L, jdbc.queryForObject("SELECT binding_version FROM ab_tenant_application_binding WHERE tenant_id=711", Long.class));
        var tx = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('aura.binding.actor','test:outer-caller',true)", String.class);
            String outerOperation = bindingAudit().operationId();
            jdbc.queryForObject("SELECT set_config('aura.binding.operation',?,true)", String.class, outerOperation);
            assertEquals(3, store.compareAndSetShadow(current, first, digest.apply(first), bindingAudit()).version());
            assertEquals("test:outer-caller", jdbc.queryForObject("SELECT current_setting('aura.binding.actor')", String.class));
            assertEquals(outerOperation, jdbc.queryForObject("SELECT current_setting('aura.binding.operation')", String.class));
            status.setRollbackOnly();
        });
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history WHERE tenant_id=711", Integer.class));
        // A storage-level activation is only a fixture for the shadow API boundary, not admission proof.
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('aura.binding.actor','test:activation-fixture',true)", String.class);
            jdbc.queryForObject("SELECT set_config('aura.binding.operation',?,true)", String.class, bindingAudit().operationId());
            jdbc.update("UPDATE ab_tenant_application_binding SET status='active',binding_version=3 WHERE tenant_id=711");
        });
        var active = new TenantApplicationShadowBindingStore.Binding(711, applicationId, winner, digest.apply(winner), 1, "active", 3);
        assertThrows(IllegalArgumentException.class, () -> store.compareAndSetShadow(active, first, digest.apply(first), bindingAudit()));
        var forgedShadow = new TenantApplicationShadowBindingStore.Binding(711, applicationId, winner, digest.apply(winner), 1, "shadow", 3);
        assertThrows(TenantApplicationShadowBindingStore.BindingConflictException.class,
                () -> store.compareAndSetShadow(forgedShadow, first, digest.apply(first), bindingAudit()));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history WHERE tenant_id=711", Integer.class));
        verifyShadowRoles(source, applicationId, first, second);
        verifyShadowLoginPool(source, applicationId, first, second);
    }
    private void verifyShadowLoginPool(DriverManagerDataSource source, long applicationId, String first, String second) throws Exception {
        var jdbc = new JdbcTemplate(source);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String runtime = "shadow_read_" + suffix;
        String writer = "shadow_write_" + suffix;
        String password = UUID.randomUUID().toString().replace("-", "");
        var secret = java.nio.file.Files.createTempFile("shadow-pool-secret-", ".txt",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        java.nio.file.Files.writeString(secret, password + "\n");
        boolean runtimeCreated = false;
        boolean writerCreated = false;
        try {
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + runtime + " NOLOGIN"); runtimeCreated = true;
                statement.execute("CREATE ROLE " + writer + " LOGIN PASSWORD '" + password + "'"); writerCreated = true;
            }
            String schema = jdbc.queryForObject("SELECT current_schema()", String.class);
            String owner = jdbc.queryForObject("SELECT current_user", String.class);
            var process = new ProcessBuilder("node", "../scripts/application/shadow-binding-role-policy.mjs",
                    schema, runtime, writer, owner).redirectErrorStream(true).start();
            String policy = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), policy); jdbc.execute(policy);
            jdbc.update("INSERT INTO ab_tenant VALUES (713)");
            var environment = new org.springframework.mock.env.MockEnvironment()
                    .withProperty("aura.binding.shadow.enabled", "true")
                    .withProperty("aura.binding.shadow.jdbc-url", source.getUrl())
                    .withProperty("aura.binding.shadow.username", writer)
                    .withProperty("aura.binding.shadow.password-file", secret.toString())
                    .withProperty("spring.datasource.username", runtime);
            String firstDigest = jdbc.queryForObject("SELECT digest FROM ab_application_release WHERE release_id=?", String.class, first);
            String secondDigest = jdbc.queryForObject("SELECT digest FROM ab_application_release WHERE release_id=?", String.class, second);
            try (var disabled = new TenantApplicationShadowBindingService(new org.springframework.mock.env.MockEnvironment())) {
                assertThrows(IllegalStateException.class, () -> disabled.createShadow(713, applicationId, first, firstDigest, bindingAudit()));
            }
            try (var service = new TenantApplicationShadowBindingService(environment)) {
                var created = service.createShadow(713, applicationId, first, firstDigest, bindingAudit());
                assertEquals(2, service.compareAndSetShadow(created, second, secondDigest, bindingAudit()).version());
                assertEquals(second, jdbc.queryForObject("SELECT current_release_id FROM ab_tenant_application_binding WHERE tenant_id=713", String.class));
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history WHERE tenant_id=713", Integer.class));
            }
            jdbc.execute("GRANT UPDATE(status) ON ab_tenant_application_binding TO " + writer);
            var failure = assertThrows(IllegalStateException.class, () -> new TenantApplicationShadowBindingService(environment));
            assertTrue(failure.getMessage().contains("column privileges"));
            jdbc.execute("REVOKE UPDATE(status) ON ab_tenant_application_binding FROM " + writer);
            jdbc.execute("ALTER TABLE ab_tenant_application_binding DISABLE ROW LEVEL SECURITY");
            failure = assertThrows(IllegalStateException.class, () -> new TenantApplicationShadowBindingService(environment));
            assertTrue(failure.getMessage().contains("row security"));
            jdbc.execute("ALTER TABLE ab_tenant_application_binding ENABLE ROW LEVEL SECURITY");
            jdbc.execute("ALTER POLICY binding_shadow_update ON ab_tenant_application_binding USING (true)");
            failure = assertThrows(IllegalStateException.class, () -> new TenantApplicationShadowBindingService(environment));
            assertTrue(failure.getMessage().contains("row security"));
            jdbc.execute("ALTER POLICY binding_shadow_update ON ab_tenant_application_binding USING (status='shadow')");
            jdbc.execute("ALTER ROLE " + writer + " CREATEDB");
            failure = assertThrows(IllegalStateException.class, () -> new TenantApplicationShadowBindingService(environment));
            assertTrue(failure.getMessage().contains("least-privilege"));
            jdbc.execute("ALTER ROLE " + writer + " NOCREATEDB");
            environment.setProperty("spring.datasource.username", writer);
            assertThrows(IllegalArgumentException.class, () -> new TenantApplicationShadowBindingService(environment));
        } finally {
            java.nio.file.Files.deleteIfExists(secret);
            if (writerCreated) { jdbc.execute("DROP OWNED BY " + writer); jdbc.execute("DROP ROLE " + writer); }
            if (runtimeCreated) { jdbc.execute("DROP OWNED BY " + runtime); jdbc.execute("DROP ROLE " + runtime); }
        }
    }
    private void verifyShadowRoles(DriverManagerDataSource source, long applicationId, String first, String second) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String runtime = "binding_runtime_" + suffix;
        String writer = "binding_shadow_" + suffix;
        try (var connection = source.getConnection()) {
            var ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
            var manager = new DataSourceTransactionManager(ds);
            var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
            definition.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            var transaction = manager.getTransaction(definition);
            var jdbc = new JdbcTemplate(ds);
            try {
                String owner = jdbc.queryForObject("SELECT current_user", String.class);
                String schema = jdbc.queryForObject("SELECT current_schema()", String.class);
                jdbc.execute("CREATE ROLE " + runtime + " NOLOGIN");
                jdbc.execute("CREATE ROLE " + writer + " NOLOGIN");
                jdbc.update("INSERT INTO ab_tenant VALUES (712)");
                jdbc.execute("GRANT UPDATE(status) ON ab_tenant_application_binding TO " + writer);
                var process = new ProcessBuilder("node", "../scripts/application/shadow-binding-role-policy.mjs",
                        schema, runtime, writer, owner).redirectErrorStream(true).start();
                String policy = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.waitFor(), policy);
                String body = policy.replace("\nBEGIN;\n", "\n").replace("\nCOMMIT;\n", "\n");
                jdbc.execute(body);
                var savepoint = connection.setSavepoint();
                jdbc.execute("CREATE POLICY unexpected_permissive ON ab_tenant_application_binding USING (true)");
                var failure = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute(body));
                assertTrue(failure.getMostSpecificCause().getMessage().contains("Unknown binding row policy"));
                connection.rollback(savepoint); connection.releaseSavepoint(savepoint);
                jdbc.execute("SET LOCAL ROLE " + runtime);
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding", Integer.class));
                assertDenied(connection, jdbc, "UPDATE ab_tenant_application_binding SET binding_version=binding_version+1");
                assertDenied(connection, jdbc, "DELETE FROM ab_tenant_application_binding_history");
                jdbc.execute("RESET ROLE"); jdbc.execute("SET LOCAL ROLE " + writer);
                var store = new TenantApplicationShadowBindingStore(jdbc);
                String firstDigest = jdbc.queryForObject("SELECT digest FROM ab_application_release WHERE release_id=?", String.class, first);
                String secondDigest = jdbc.queryForObject("SELECT digest FROM ab_application_release WHERE release_id=?", String.class, second);
                var created = store.createShadow(712, applicationId, first, firstDigest, bindingAudit());
                assertEquals(created, store.createShadow(712, applicationId, first, firstDigest, bindingAudit()));
                assertEquals(2, store.compareAndSetShadow(created, second, secondDigest, bindingAudit()).version());
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_tenant_application_binding_history WHERE tenant_id=712", Integer.class));
                // No status predicate in the caller: row security still protects the active binding.
                assertEquals(0, jdbc.update("UPDATE ab_tenant_application_binding SET current_release_id=?,binding_version=binding_version+1 WHERE tenant_id=711", first));
                assertDenied(connection, jdbc, "UPDATE ab_tenant_application_binding SET status='active',binding_version=binding_version+1 WHERE tenant_id=712");
                assertDenied(connection, jdbc, "ALTER TABLE ab_tenant_application_binding DISABLE ROW LEVEL SECURITY");
                assertDenied(connection, jdbc, "TRUNCATE ab_tenant_application_binding_history");
                savepoint = connection.setSavepoint();
                failure = assertThrows(org.springframework.dao.DataAccessException.class,
                        () -> jdbc.execute("INSERT INTO ab_tenant_application_binding_history SELECT * FROM ab_tenant_application_binding_history"));
                assertTrue(failure.getMostSpecificCause().getMessage().contains("immutable transition projection"));
                connection.rollback(savepoint); connection.releaseSavepoint(savepoint);
                jdbc.execute("RESET ROLE");
            } finally { manager.rollback(transaction); }
        }
    }
    private static TenantApplicationShadowBindingStore.AuditContext bindingAudit() {
        return new TenantApplicationShadowBindingStore.AuditContext("test:binding-operator",
                com.auraboot.framework.common.util.UlidGenerator.generate());
    }
    private static byte[] readBytes(java.io.InputStream stream) {
        try { return stream.readAllBytes(); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    public static class RegistryFixturePlugin extends org.pf4j.Plugin {
        public RegistryFixturePlugin(org.pf4j.PluginWrapper wrapper) { super(wrapper); }
    }
    public static class RegistryFixtureHandler implements com.auraboot.framework.plugin.extension.CommandHandlerExtension {
        public String getCommandType() { return "fixture:contract"; }
        public java.util.Set<String> getSupportedContracts(String commandType) {
            return supports(commandType) ? java.util.Set.of("contract-v1") : java.util.Set.of();
        }
        public Object execute(CommandContext context) { throw new AssertionError("Observation must not execute a command"); }
    }
    private static java.nio.file.Path writeRegistryFixtureJar(java.nio.file.Path root) throws Exception {
        var manifest = new java.util.jar.Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Plugin-Id", "registry-fixture");
        attributes.putValue("Plugin-Version", "1.0.0");
        attributes.putValue("Plugin-Class", RegistryFixturePlugin.class.getName());
        var jar = root.resolve("registry-fixture.jar");
        try (var output = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar), manifest)) {
            for (var entry : List.of(RegistryFixturePlugin.class, RegistryFixtureHandler.class)) {
                String name = entry.getName().replace('.', '/') + ".class";
                output.putNextEntry(new java.util.jar.JarEntry(name));
                try (var input = entry.getClassLoader().getResourceAsStream(name)) {
                    assertNotNull(input); input.transferTo(output);
                }
                output.closeEntry();
            }
            output.putNextEntry(new java.util.jar.JarEntry("META-INF/extensions.idx"));
            output.write((RegistryFixtureHandler.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
    private static void verifyRegisteredDefinitionInspection(ApplicationReleaseRegistrationService service, JdbcTemplate jdbc) throws Exception {
        var mapper = new ObjectMapper();
        var contract = mapper.createObjectNode().put("schemaVersion", 1);
        contract.putArray("requiredCapabilities").addObject().put("kind", "handler").put("key", "fixture:contract").put("contract", "contract-v1");
        contract.withArray("requiredCapabilities").addObject().put("kind", "web").put("key", "fixture:page").put("contract", "page-v1");
        String artifactDigest = "sha256:18fc4f981b31590710a71c38af3c72c13aa5a53d92eb72e96ec1be995e9645d5";
        var content = new Content(1, "1.0", "sha256:" + "a".repeat(64),
                mapper.createObjectNode().put("runtime", "v1").put("pluginApi", "v1").put("dslSchema", 1),
                List.of(new Component("pinned-definition", "definition", "1.0.0", artifactDigest, contract)));
        var registered = service.register("aura-edu", "pinned-inspection", content, "test:publisher");
        var directory = java.nio.file.Files.createTempDirectory("registered-definition-it-");
        var pluginRoot = java.nio.file.Files.createTempDirectory("registered-handler-it-");
        try {
            java.nio.file.Files.createDirectory(directory.resolve("a"));
            java.nio.file.Files.writeString(directory.resolve("a/commands.json"), "[{\"code\":\"fixture:business\",\"handler\":\"fixture:contract\"}]");
            java.nio.file.Files.writeString(directory.resolve("a.json"), "{}");
            java.nio.file.Files.writeString(directory.resolve("plugin.json"), "{\"pluginId\":\"test.pinned\",\"version\":\"1.0.0\",\"resourceDirs\":{\"commands\":\"a/commands.json\"}}");
            var jar = writeRegistryFixtureJar(pluginRoot);
            String jarDigest = "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(java.nio.file.Files.readAllBytes(jar)));
            var manager = new com.auraboot.framework.plugin.pf4j.AuraPluginManager(pluginRoot.toString());
            var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
            var registry = new com.auraboot.framework.plugin.pf4j.ExtensionRegistry(manager,
                    beans.getBeanProvider(com.auraboot.framework.plugin.extension.CommandHandlerExtension.class),
                    beans.getBeanProvider(com.auraboot.framework.plugin.extension.ServiceTaskActionExtension.class));
            try {
                manager.init();
                assertSame(manager.getPlugin("registry-fixture").getPluginClassLoader(), registry.getCommandHandler("fixture:contract").orElseThrow().getClass().getClassLoader());
                var inspector = new RegisteredDefinitionHandlerInspector(jdbc, mapper, new HandlerContractInspector(registry, manager));
                var result = inspector.inspect(registered.releaseId(), registered.digest(), "pinned-definition", directory);
                assertEquals(registered.digest(), result.releaseDigest());
                assertEquals(artifactDigest, result.handlerObservation().definitionDigest());
                assertEquals("fixture:contract", result.handlerObservation().observation().dependencies().references().getFirst().handlerCode());
                assertTrue(result.handlerObservation().observation().findings().isEmpty());
                assertEquals(List.of(jarDigest), result.handlerObservation().observation().capabilities().getFirst().providerDigests());
                var handlerOnly = contract.deepCopy();
                handlerOnly.withArray("requiredCapabilities").remove(1);
                var firstDefinition = new Component("definition-a", "definition", "1.0.0", artifactDigest, handlerOnly);
                var secondDefinition = new Component("definition-b", "definition", "1.0.0", artifactDigest, handlerOnly);
                var aggregateContent = new Content(1, "aggregate", content.sourceLockIdentity(), content.platformCompatibility(),
                        List.of(firstDefinition, secondDefinition));
                var aggregateRelease = service.register("aura-edu", "aggregate-inspection", aggregateContent, "test:publisher");
                var artifactMap = java.util.Map.of("definition-a", directory, "definition-b", directory);
                var aggregate = inspector.inspectRelease(aggregateRelease.releaseId(), aggregateRelease.digest(), artifactMap);
                assertEquals(List.of("definition-a", "definition-b"), aggregate.definitions().stream().map(RegisteredDefinitionHandlerInspector.Result::componentKey).toList());
                assertTrue(aggregate.findings().isEmpty());
                assertTrue(aggregate.unobservedComponents().isEmpty());
                assertEquals(1, aggregate.capabilities().size());
                assertEquals(List.of(jarDigest), aggregate.capabilities().getFirst().providerDigests());
                assertThrows(UnsupportedOperationException.class, () -> aggregate.capabilities().clear());
                verifyRegisteredCapabilityCli(jdbc, aggregateRelease, aggregate, jarDigest, true);
                assertThrows(IllegalArgumentException.class, () -> inspector.inspectRelease(aggregateRelease.releaseId(), aggregateRelease.digest(), java.util.Map.of("definition-a", directory)));
                assertThrows(IllegalArgumentException.class, () -> inspector.inspectRelease(aggregateRelease.releaseId(), aggregateRelease.digest(), java.util.Map.of("definition-a", directory, "definition-b", directory, "extra", directory)));
                var withAsset = service.register("aura-edu", "aggregate-unobserved", new Content(1, "aggregate-asset", content.sourceLockIdentity(), content.platformCompatibility(),
                        List.of(firstDefinition, secondDefinition, new Component("asset-c", "asset", "1.0.0", "sha256:" + "e".repeat(64), handlerOnly))), "test:publisher");
                var partial = inspector.inspectRelease(withAsset.releaseId(), withAsset.digest(), artifactMap);
                assertEquals("asset-c", partial.unobservedComponents().getFirst().key());
                assertTrue(partial.capabilities().isEmpty());
                assertEquals(List.of(new RegisteredDefinitionHandlerInspector.Finding("asset-c", "component-type-unobserved")), partial.findings());
                var webPending = inspector.inspectRelease(registered.releaseId(), registered.digest(), java.util.Map.of("pinned-definition", directory));
                assertEquals(List.of(new RegisteredDefinitionHandlerInspector.Finding("pinned-definition", "capability-unobserved:web:fixture:page:page-v1")), webPending.findings());
                assertTrue(webPending.capabilities().isEmpty());
                var changingHandlers = new HandlerContractInspector(registry, manager) {
                    private boolean changed;
                    @Override public DefinitionArtifactObservation observeDefinitionArtifact(java.nio.file.Path path,
                            String digest, List<Requirement> requirements) throws java.io.IOException {
                        var observed = super.observeDefinitionArtifact(path, digest, requirements);
                        if (!changed) { changed = true; manager.stopPlugin("registry-fixture"); }
                        return observed;
                    }
                };
                var mixed = new RegisteredDefinitionHandlerInspector(jdbc, mapper, changingHandlers)
                        .inspectRelease(aggregateRelease.releaseId(), aggregateRelease.digest(), artifactMap);
                assertFalse(mixed.definitions().getFirst().handlerObservation().observation().capabilities().isEmpty());
                assertTrue(mixed.findings().stream().anyMatch(f -> f.code().equals("release-handler-generation-changed")));
                assertTrue(mixed.capabilities().isEmpty());
                verifyRegisteredCapabilityCli(jdbc, aggregateRelease, mixed, jarDigest, false);
                assertEquals(org.pf4j.PluginState.STARTED, manager.startPlugin("registry-fixture"));

                assertEquals(org.pf4j.PluginState.STOPPED, manager.stopPlugin("registry-fixture"));
                var stopped = inspector.inspect(registered.releaseId(), registered.digest(), "pinned-definition", directory);
                assertTrue(stopped.handlerObservation().observation().capabilities().isEmpty());
                var stoppedAggregate = inspector.inspectRelease(aggregateRelease.releaseId(), aggregateRelease.digest(), artifactMap);
                assertEquals(2, stoppedAggregate.findings().size());
                verifyRegisteredCapabilityCli(jdbc, aggregateRelease, stoppedAggregate, jarDigest, false);
                assertTrue(stoppedAggregate.findings().stream().allMatch(f -> f.code().contains("primary-handler-missing")));

                assertTrue(stopped.handlerObservation().observation().findings().stream().anyMatch(f -> f.contains("primary-handler-missing")));
                assertEquals(org.pf4j.PluginState.STARTED, manager.startPlugin("registry-fixture"));
                assertTrue(inspector.inspect(registered.releaseId(), registered.digest(), "pinned-definition", directory).handlerObservation().observation().findings().isEmpty());
                assertEquals(List.of(new RegisteredDefinitionHandlerInspector.Requirement("web", "fixture:page", "page-v1")), result.unobservedRequirements());
                assertThrows(IllegalStateException.class, () -> inspector.inspect(registered.releaseId(), "sha256:" + "0".repeat(64), "pinned-definition", directory));
                assertThrows(IllegalArgumentException.class, () -> inspector.inspect(registered.releaseId(), registered.digest(), "absent", directory));
                java.nio.file.Files.writeString(directory.resolve("a/commands.json"), "[]");
                assertThrows(IllegalArgumentException.class, () -> inspector.inspect(registered.releaseId(), registered.digest(), "pinned-definition", directory));
                var emptyContract = mapper.createObjectNode().put("schemaVersion", 1);
                emptyContract.putArray("requiredCapabilities");
                var emptyContent = new Content(1, "1.1", content.sourceLockIdentity(), content.platformCompatibility(),
                        List.of(new Component("pinned-definition", "definition", "1.1.0",
                                "sha256:74b5129b34e5f9964c4e144906250f96b88a545af0bac75e860f416e812be13d", emptyContract)));
                var emptyRelease = service.register("aura-edu", "empty-definition-inspection", emptyContent, "test:publisher");
                var emptyResult = inspector.inspect(emptyRelease.releaseId(), emptyRelease.digest(), "pinned-definition", directory);
                assertTrue(emptyResult.handlerObservation().observation().findings().isEmpty());
                assertTrue(emptyResult.handlerObservation().observation().dependencies().references().isEmpty());
                assertTrue(emptyResult.unobservedRequirements().isEmpty());
            } finally {
                registry.detachLifecycleListener();
                manager.cleanup();
                manager.unloadPlugins();
            }
        } finally {
            for (var ownedRoot : List.of(directory, pluginRoot)) {
                try (var paths = java.nio.file.Files.walk(ownedRoot)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
                }
            }
        }
    }
    private static void verifyRegisteredCapabilityCli(JdbcTemplate jdbc, Registration application,
            RegisteredDefinitionHandlerInspector.ReleaseObservation observation, String jarDigest, boolean available) throws Exception {
        var mapper = new ObjectMapper();
        var contracts = mapper.createObjectNode();
        contracts.putArray("runtime").add("v1"); contracts.putArray("pluginApi").add("v1"); contracts.putArray("dslSchema").add(1);
        var platformService = new PlatformReleaseRegistrationService(jdbc, mapper);
        var platform = platformService.register("observed-platform", new PlatformReleaseRegistrationService.Content(
                "1.0.0", "sha256:" + "a".repeat(64), contracts,
                List.of(new PlatformReleaseRegistrationService.Artifact("plugin", "registry-fixture", "1.0.0",
                        "artifact:registry-fixture/1.0.0", jarDigest,
                        new PlatformReleaseRegistrationService.Source("core", "a".repeat(40))))), "test:observation");
        var evidence = java.nio.file.Path.of(required("AURA_EVIDENCE_ROOT"), "migrator-runtime",
                "registered-capability-chain-" + UUID.randomUUID());
        java.nio.file.Files.createDirectories(evidence);
        var appFile = evidence.resolve("application.json");
        var platformFile = evidence.resolve("platform.json");
        // Re-read the database's exact raw bytes; do not reconstruct a manifest from DTOs.
        java.nio.file.Files.writeString(appFile, jdbc.queryForObject(
                "SELECT manifest_text FROM ab_application_release WHERE release_id=?", String.class, application.releaseId()));
        java.nio.file.Files.writeString(platformFile, jdbc.queryForObject(
                "SELECT manifest_text FROM ab_platform_release_registry WHERE release_id=?", String.class, platform.releaseId()));
        var reqFile = evidence.resolve("requirements.json");
        runNode(evidence, "extract", reqFile, 0, "../scripts/application/application-release-requirements.mjs",
                appFile.toString(), application.digest());
        var deployment = mapper.createObjectNode().put("schemaVersion", 1)
                .put("deploymentId", "01ARZ3NDEKTSV4RRFFQ69G5FAV").put("generation", 1);
        deployment.putObject("platformRelease").put("releaseId", platform.releaseId()).put("digest", platform.digest());
        deployment.set("platformContracts", contracts.deepCopy());
        deployment.putArray("supportedApplications").addObject().put("application", "aura-edu").putArray("compatibilityEpochs").add(1);
        deployment.set("capabilities", mapper.valueToTree(observation.capabilities()));
        var expected = mapper.createObjectNode().put("applicationReleaseDigest", application.digest())
                .put("platformReleaseDigest", platform.digest()).put("requirementsDigest", digestBytes(java.nio.file.Files.readAllBytes(reqFile)))
                .put("deploymentId", "01ARZ3NDEKTSV4RRFFQ69G5FAV").put("generation", 1);
        for (String scenario : available ? List.of("observed", "wrong-platform", "extra-contract") : List.of("stopped")) {
            var candidate = deployment.deepCopy();
            if (scenario.equals("wrong-platform")) ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("platformRelease"))
                    .put("releaseId", "01ARZ3NDEKTSV4RRFFQ69G5FAW");
            if (scenario.equals("extra-contract")) ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("platformContracts"))
                    .withArray("runtime").add("undeclared-v2");
            var depFile = evidence.resolve(scenario + "-deployment.json");
            var pinFile = evidence.resolve(scenario + "-expected.json");
            var output = evidence.resolve(scenario + "-result.json");
            java.nio.file.Files.writeString(depFile, candidate.toString());
            expected.put("deploymentDigest", digestBytes(java.nio.file.Files.readAllBytes(depFile)));
            java.nio.file.Files.writeString(pinFile, expected.toString());
            runNode(evidence, scenario, output, scenario.equals("observed") ? 0 : 1,
                    "../scripts/application/deployment-capability-verifier.mjs", "--registered", appFile.toString(),
                    platformFile.toString(), depFile.toString(), pinFile.toString());
            if (scenario.equals("wrong-platform")) {
                assertTrue(java.nio.file.Files.readString(evidence.resolve(scenario + ".stderr")).contains("release ID mismatch"));
                continue;
            }
            var result = mapper.readTree(java.nio.file.Files.readString(output));
            assertEquals(scenario.equals("observed"), result.path("capabilitiesSatisfied").asBoolean());
            assertFalse(result.has("admitted"));
            if (scenario.equals("observed")) assertEquals(jarDigest, result.path("resolved").get(0).path("providerDigests").get(0).asText());
            else assertEquals(scenario.equals("stopped") ? "required-capability-missing" : "platform-contract-not-registered",
                    result.path("findings").get(0).path("code").asText());
        }
        verifyAttestedCapabilityCli(evidence, available, jarDigest);
        java.nio.file.Files.writeString(evidence.resolve("scope.json"), mapper.createObjectNode()
                .put("applicationReleaseId", application.releaseId()).put("platformReleaseId", platform.releaseId())
                .put("handlerGeneration", observation.handlerGeneration()).put("pluginAvailable", available)
                .put("deploymentIdentityIsFixture", true).put("admissionVerified", false).toPrettyString());
    }

    private static void verifyAttestedCapabilityCli(java.nio.file.Path evidence, boolean available, String jarDigest) throws Exception {
        var mapper = new ObjectMapper();
        String scenario = available ? "observed" : "stopped";
        var deployment = evidence.resolve(scenario + "-deployment.json");
        var expected = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                java.nio.file.Files.readString(evidence.resolve(scenario + "-expected.json")));
        var keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        long now = java.time.Instant.now().getEpochSecond();
        var fields = mapper.createObjectNode().put("schemaVersion", 1).put("keyId", "java-test-observer")
                .put("deploymentDigest", expected.path("deploymentDigest").asText())
                .put("deploymentId", expected.path("deploymentId").asText()).put("generation", 1)
                .put("issuedAt", now - 1).put("expiresAt", now + 59);
        var signature = java.security.Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(("auraboot.deployment-capabilities.attestation.v1\n" + fields).getBytes(StandardCharsets.UTF_8));
        var envelope = fields.deepCopy().put("signature", java.util.Base64.getEncoder().encodeToString(signature.sign()));
        var envelopeFile = evidence.resolve("attestation.json");
        java.nio.file.Files.writeString(envelopeFile, envelope.toString());
        var policy = mapper.createObjectNode().put("schemaVersion", 1);
        var key = policy.putArray("keys").addObject().put("keyId", "java-test-observer")
                .put("publicKey", "-----BEGIN PUBLIC KEY-----\n"
                        + java.util.Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n")
                .put("revoked", false).put("maxAgeSeconds", 60);
        key.putArray("deploymentIds").add(expected.path("deploymentId").asText());
        key.putArray("platformReleaseDigests").add(expected.path("platformReleaseDigest").asText());
        var policyFile = evidence.resolve("trust-policy.json");
        var expectedFile = evidence.resolve("attested-expected.json");
        java.nio.file.Files.writeString(policyFile, policy.toString());
        expected.put("trustPolicyDigest", digestBytes(java.nio.file.Files.readAllBytes(policyFile)));
        java.nio.file.Files.writeString(expectedFile, expected.toString());
        var output = evidence.resolve("attested-result.json");
        runNode(evidence, "attested", output, available ? 0 : 1,
                "../scripts/application/deployment-observation-attestation.mjs", evidence.resolve("application.json").toString(),
                evidence.resolve("platform.json").toString(), deployment.toString(), envelopeFile.toString(),
                policyFile.toString(), expectedFile.toString());
        var result = mapper.readTree(java.nio.file.Files.readString(output));
        assertEquals(available, result.path("capabilitiesSatisfied").asBoolean());
        assertEquals("java-test-observer", result.path("observationAttestation").path("keyId").asText());
        assertFalse(result.has("admitted"));
        if (available) assertEquals(jarDigest, result.path("resolved").get(0).path("providerDigests").get(0).asText());
        else assertEquals("required-capability-missing", result.path("findings").get(0).path("code").asText());
        key.put("revoked", true);
        var revokedPolicy = evidence.resolve("revoked-policy.json");
        var revokedExpected = evidence.resolve("revoked-expected.json");
        java.nio.file.Files.writeString(revokedPolicy, policy.toString());
        expected.put("trustPolicyDigest", digestBytes(java.nio.file.Files.readAllBytes(revokedPolicy)));
        java.nio.file.Files.writeString(revokedExpected, expected.toString());
        runNode(evidence, "revoked-attestation", evidence.resolve("revoked-result.json"), 1,
                "../scripts/application/deployment-observation-attestation.mjs", evidence.resolve("application.json").toString(),
                evidence.resolve("platform.json").toString(), deployment.toString(), envelopeFile.toString(),
                revokedPolicy.toString(), revokedExpected.toString());
        assertTrue(java.nio.file.Files.readString(evidence.resolve("revoked-attestation.stderr")).contains("not authorized"));
    }

    private static String digestBytes(byte[] bytes) throws Exception {
        return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void runNode(java.nio.file.Path evidence, String name, java.nio.file.Path output,
            int expectedExit, String... args) throws Exception {
        var command = new java.util.ArrayList<String>(); command.add("node"); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectOutput(output.toFile())
                .redirectError(evidence.resolve(name + ".stderr").toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly(); fail("Owned Node verifier timed out: " + name);
        }
        assertEquals(expectedExit, process.exitValue(), () -> "Node verifier exit mismatch: " + name + "; evidence=" + evidence);
    }

    private static void verifyPrivateLoginPool(DriverManagerDataSource source, String schema) throws Exception {
        var jdbc = new JdbcTemplate(source);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String runtime = "rf_pool_runtime_" + suffix;
        String registrar = "rf_pool_registrar_" + suffix;
        String password = UUID.randomUUID().toString().replace("-", "");
        var secret = java.nio.file.Files.createTempFile("registry-pool-secret-", ".txt",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        java.nio.file.Files.writeString(secret, password + "\n");
        boolean runtimeCreated = false;
        boolean registrarCreated = false;
        try {
            // Plain JDBC avoids including the credential-bearing statement in Spring SQL logs.
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + runtime + " NOLOGIN");
                runtimeCreated = true;
                statement.execute("CREATE ROLE " + registrar + " LOGIN PASSWORD '" + password + "'");
                registrarCreated = true;
            }
            String owner = jdbc.queryForObject("SELECT current_user", String.class);
            var process = new ProcessBuilder("node", "../scripts/application/registry-role-policy.mjs",
                    schema, runtime, registrar, owner).redirectErrorStream(true).start();
            String policy = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), policy);
            jdbc.execute(policy);
            var environment = new org.springframework.mock.env.MockEnvironment()
                    .withProperty("aura.registry.registration.enabled", "true")
                    .withProperty("aura.registry.registration.jdbc-url", source.getUrl())
                    .withProperty("aura.registry.registration.username", registrar)
                    .withProperty("aura.registry.registration.password-file", secret.toString())
                    .withProperty("spring.datasource.username", runtime);
            var service = new ApplicationReleaseRegistrationService(environment, new ObjectMapper());
            try {
                service.createApplication("pool-app", "Private pool", "test:pool");
                Registration registered = service.register("pool-app", "pool-build", content("b"), "test:pool");
                assertEquals(1, registered.sequence());
                // This observer uses a different connection and must see the committed result.
                assertEquals("test:pool", jdbc.queryForObject(
                        "SELECT registered_by FROM ab_application_release WHERE release_id=?", String.class, registered.releaseId()));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT count(*) FROM ab_application_release_component WHERE release_id=?", Integer.class, registered.releaseId()));
            } finally { service.close(); }
            var platform = new PlatformReleaseRegistrationService(environment, new ObjectMapper());
            try {
                var contracts = new ObjectMapper().createObjectNode();
                contracts.putArray("runtime").add("runtime-v1");
                contracts.putArray("pluginApi").add("api-v1");
                contracts.putArray("dslSchema").add(4);
                var artifact = new PlatformReleaseRegistrationService.Artifact("runtime", "core", "1.0.0",
                        "artifact:core", "sha256:" + "a".repeat(64),
                        new PlatformReleaseRegistrationService.Source("core", "a".repeat(40)));
                var registered = platform.register("pool-platform", new PlatformReleaseRegistrationService.Content(
                        "1.0.0", "sha256:" + "b".repeat(64), contracts, List.of(artifact)), "test:pool");
                assertEquals("test:pool", jdbc.queryForObject("SELECT registered_by FROM ab_platform_release_registry WHERE release_id=?",
                        String.class, registered.releaseId()));
                var conflict = new PlatformReleaseRegistrationService.Artifact("runtime", "core", "1.0.0",
                        "artifact:core", "sha256:" + "c".repeat(64), artifact.source());
                assertThrows(PlatformReleaseRegistrationService.ArtifactCoordinateConflictException.class,
                        () -> platform.register("pool-platform", new PlatformReleaseRegistrationService.Content(
                                "2.0.0", "sha256:" + "b".repeat(64), contracts, List.of(conflict)), "test:conflict"));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_platform_release_registry WHERE platform_code='pool-platform'", Integer.class));
                platform.register("pool-platform", new PlatformReleaseRegistrationService.Content(
                        "2.0.0", "sha256:" + "b".repeat(64), contracts, List.of(artifact)), "test:reuse");
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_platform_release_registry WHERE platform_code='pool-platform'", Integer.class));
            } finally { platform.close(); }
            jdbc.execute("GRANT UPDATE(name) ON ab_application TO " + registrar);
            var columnFailure = assertThrows(IllegalStateException.class,
                    () -> new ApplicationReleaseRegistrationService(environment, new ObjectMapper()));
            assertTrue(columnFailure.getMessage().contains("column or sequence"));
            jdbc.execute("REVOKE UPDATE(name) ON ab_application FROM " + registrar);
            jdbc.execute("ALTER ROLE " + registrar + " CREATEDB");
            var privilegeFailure = assertThrows(IllegalStateException.class,
                    () -> new ApplicationReleaseRegistrationService(environment, new ObjectMapper()));
            assertTrue(privilegeFailure.getMessage().contains("least-privilege"));
            jdbc.execute("ALTER ROLE " + registrar + " NOCREATEDB");
        } finally {
            java.nio.file.Files.deleteIfExists(secret);
            // Only roles created by this fixture are removed; the runtime database is retained.
            if (registrarCreated) {
                jdbc.execute("DROP OWNED BY " + registrar);
                jdbc.execute("DROP ROLE " + registrar);
            }
            if (runtimeCreated) {
                jdbc.execute("DROP OWNED BY " + runtime);
                jdbc.execute("DROP ROLE " + runtime);
            }
        }
    }

    private static void verifyDatabaseRoles(DriverManagerDataSource source, String schema) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String runtime = "rf_runtime_" + suffix;
        String registrar = "rf_registrar_" + suffix;
        try (var connection = source.getConnection()) {
            var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
            var transactionManager = new DataSourceTransactionManager(dataSource);
            var transaction = transactionManager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
            var jdbc = new JdbcTemplate(dataSource);
            try {
                String owner = jdbc.queryForObject("SELECT current_user", String.class);
                jdbc.execute("CREATE ROLE " + runtime + " NOLOGIN");
                jdbc.execute("CREATE ROLE " + registrar + " NOLOGIN");
                // A stale column grant must also be revoked by the policy.
                jdbc.execute("GRANT UPDATE(name) ON ab_application TO " + runtime);
                var process = new ProcessBuilder("node", "../scripts/application/registry-role-policy.mjs",
                        schema, runtime, registrar, owner).redirectErrorStream(true).start();
                String policy = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.waitFor(), policy);
                // Keep test-only roles and grants inside this test's rollback transaction.
                jdbc.execute(policy.replace("\nBEGIN;\n", "\n").replace("\nCOMMIT;\n", "\n"));
                var membershipSavepoint = connection.setSavepoint();
                jdbc.execute("GRANT " + registrar + " TO " + runtime);
                var membershipFailure = assertThrows(org.springframework.dao.DataAccessException.class,
                        () -> jdbc.execute(policy.replace("\nBEGIN;\n", "\n").replace("\nCOMMIT;\n", "\n")));
                assertTrue(membershipFailure.getMostSpecificCause().getMessage().contains("no inherited or assumable roles"));
                connection.rollback(membershipSavepoint);
                connection.releaseSavepoint(membershipSavepoint);
                jdbc.execute("SET LOCAL ROLE " + runtime);
                assertTrue(jdbc.queryForObject("SELECT count(*) FROM ab_application", Integer.class) > 0);
                assertDenied(connection, jdbc, "INSERT INTO ab_application(code,name,created_by) VALUES ('role-app','Role','test:role')");
                assertDenied(connection, jdbc, "UPDATE ab_application SET name='Changed'");
                assertDenied(connection, jdbc, "ALTER TABLE ab_application ADD COLUMN unwanted TEXT");
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_platform_release_registry", Integer.class));
                assertDenied(connection, jdbc, "DELETE FROM ab_platform_release_registry");
                assertDenied(connection, jdbc, "TRUNCATE ab_platform_release_registry");
                jdbc.execute("RESET ROLE");
                jdbc.execute("SET LOCAL ROLE " + registrar);
                assertEquals(1, jdbc.update("INSERT INTO ab_application(code,name,created_by) VALUES ('role-app','Role','test:role')"));
                jdbc.queryForObject("SELECT id FROM ab_application WHERE code='role-app' FOR UPDATE", Long.class);
                var registrarService = new ApplicationReleaseRegistrationService(jdbc, new ObjectMapper());
                var registered = registrarService.register("role-app", "least-privilege", content("b"), "test:registrar");
                assertEquals(1, registered.sequence());
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_application_release_component WHERE release_id=?", Integer.class, registered.releaseId()));
                assertDenied(connection, jdbc, "UPDATE ab_application SET created_by='test:forged' WHERE code='role-app'");
                assertDenied(connection, jdbc, "DELETE FROM ab_application WHERE code='role-app'");
                assertDenied(connection, jdbc, "ALTER TABLE ab_application ADD COLUMN unwanted TEXT");
                jdbc.execute("RESET ROLE");
            } finally {
                transactionManager.rollback(transaction);
            }
        }
    }

    private static void assertDenied(java.sql.Connection connection, JdbcTemplate jdbc, String sql) throws Exception {
        var savepoint = connection.setSavepoint();
        var failure = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute(sql));
        assertInstanceOf(java.sql.SQLException.class, failure.getMostSpecificCause());
        assertEquals("42501", ((java.sql.SQLException) failure.getMostSpecificCause()).getSQLState());
        connection.rollback(savepoint);
        connection.releaseSavepoint(savepoint);
    }

    // Real MVC dispatch, interceptor and transactional persistence; role lookup is stubbed.
    // JWT authentication and database-backed RBAC require separate full-stack verification.
    private static void verifyHttpBoundary(ApplicationReleaseRegistrationService service, JdbcTemplate jdbc) throws Exception {
        var mapper = new ObjectMapper();
        var roles = org.mockito.Mockito.mock(com.auraboot.framework.application.security.AdminRoleChecker.class);
        var audit = org.mockito.Mockito.mock(com.auraboot.framework.application.security.AdminAuditService.class);
        var summarizer = org.mockito.Mockito.mock(com.auraboot.framework.application.security.RequestBodySummarizer.class);
        var interceptor = new com.auraboot.framework.application.security.AdminRoleInterceptor(roles, mapper, audit, summarizer, null);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ApplicationReleaseController(service)).addInterceptors(interceptor).build();
        String uri = "/api/admin/application-releases/aura-edu";
        var body = mapper.valueToTree(new ApplicationReleaseController.RegisterRequest("http-registration", content("b")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("actor", "user:forged");
        String payload = body.toString();
        try {
            com.auraboot.framework.application.tenant.MetaContext.clear();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri)
                    .contentType("application/json").accept("application/json").content(payload))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("409"));
            com.auraboot.framework.application.tenant.MetaContext.setContext(7L, 9L, "test-user", "publisher");
            org.mockito.Mockito.when(roles.hasRole(7L, 9L, "tenant_admin")).thenReturn(true);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri)
                    .contentType("application/json").accept("application/json").content(payload))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("409"));
            assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM ab_application_release", Integer.class));
            org.mockito.Mockito.when(roles.hasRole(7L, 9L, "platform_admin")).thenReturn(true);
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri)
                        .contentType("application/json").accept("application/json").content(payload))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.sequence").value(6))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.registeredBy").value("user:7:9"));
            }
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri)
                    .contentType("application/json").accept("application/json").content(mapper.writeValueAsString(
                            new ApplicationReleaseController.RegisterRequest("http-registration", content("c")))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("409"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri)
                    .contentType("application/json").accept("application/json").content(mapper.writeValueAsString(
                            new ApplicationReleaseController.RegisterRequest("", content("b")))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("400"));
            assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM ab_application_release", Integer.class));
            assertEquals("user:7:9", jdbc.queryForObject(
                    "SELECT registered_by FROM ab_application_release WHERE registration_key='http-registration'", String.class));
            assertEquals(7L, jdbc.queryForObject("SELECT next_release_sequence FROM ab_application", Long.class));
            String createUri = "/api/admin/application-releases";
            String createPayload = mapper.writeValueAsString(new ApplicationReleaseController.CreateApplicationRequest("http-app", "HTTP App"));
            org.mockito.Mockito.when(roles.hasRole(7L, 9L, "platform_admin")).thenReturn(false);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(createUri)
                    .contentType("application/json").accept("application/json").content(createPayload))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("409"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_application", Integer.class));
            org.mockito.Mockito.when(roles.hasRole(7L, 9L, "platform_admin")).thenReturn(true);
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(createUri)
                        .contentType("application/json").accept("application/json").content(createPayload))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.code").value("http-app"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.createdBy").value("user:7:9"));
            }
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ab_application", Integer.class));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(createUri + "/http-app")
                    .contentType("application/json").accept("application/json").content(payload))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.sequence").value(1))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.registeredBy").value("user:7:9"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_application_release r JOIN ab_application a ON a.id=r.application_id WHERE a.code='http-app'", Integer.class));
        } finally {
            com.auraboot.framework.application.tenant.MetaContext.clear();
        }
    }
    private static List<Registration> race(ApplicationReleaseRegistrationService service, String first, String second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(key -> workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.register("aura-edu", key, content("b"), "test:publisher");
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        }
    }
    private static Content content(String digestCharacter) {
        var mapper = new ObjectMapper();
        return new Content(1, "1.0", "sha256:" + "a".repeat(64),
                mapper.createObjectNode().put("runtimeContract", "1"), List.of(
                new Component("edu-core", "definition", "1.0.0", "sha256:" + digestCharacter.repeat(64), null)));
    }
    private static String required(String name) {
        String value = System.getenv(name); assertNotNull(value, name); assertFalse(value.isBlank(), name); return value;
    }
}
