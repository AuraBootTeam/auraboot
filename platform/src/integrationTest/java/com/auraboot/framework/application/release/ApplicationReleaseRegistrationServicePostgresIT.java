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
                    "V20260925040200__application_creation_actors.sql")) {
                try (var migration = getClass().getResourceAsStream("/db/migration/core/" + name)) {
                    assertNotNull(migration); jdbc.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
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
            }
        } finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
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
                assertEquals(org.pf4j.PluginState.STOPPED, manager.stopPlugin("registry-fixture"));
                var stopped = inspector.inspect(registered.releaseId(), registered.digest(), "pinned-definition", directory);
                assertTrue(stopped.handlerObservation().observation().capabilities().isEmpty());
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
