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
                    "V20260925040000__application_release_registration_keys.sql")) {
                try (var migration = getClass().getResourceAsStream("/db/migration/core/" + name)) {
                    assertNotNull(migration); jdbc.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            jdbc.update("INSERT INTO ab_application(code,name) VALUES ('aura-edu','Aura EDU')");
            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(Transactions.class);
                context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
                context.registerBean(ApplicationReleaseRegistrationService.class,
                        () -> new ApplicationReleaseRegistrationService(jdbc, new ObjectMapper()));
                context.refresh();
                var service = context.getBean(ApplicationReleaseRegistrationService.class);
                Registration first = service.register("aura-edu", "build-1", content("b"));
                assertTrue(com.auraboot.framework.common.util.UlidGenerator.isValid(first.releaseId()));
                assertEquals(1, first.sequence());
                assertEquals(first, service.register("aura-edu", "build-1", content("b")));
                assertThrows(IllegalStateException.class, () -> service.register("aura-edu", "build-1", content("c")));
                // The database rejects a changed digest for the same component coordinate.
                assertThrows(RuntimeException.class, () -> service.register("aura-edu", "changed-coordinate", content("c")));
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
                Registration normalized = service.register("aura-edu", "normalized-input", ordered);
                assertEquals(normalized, service.register("aura-edu", "normalized-input", reordered));
                assertEquals(5, normalized.sequence());
                assertThrows(IllegalArgumentException.class, () -> service.register("missing-app", "build-1", content("b")));
                assertThrows(IllegalArgumentException.class, () -> service.register("aura-edu", "", content("b")));
                assertEquals(6L, jdbc.queryForObject("SELECT next_release_sequence FROM ab_application", Long.class));
            }
        } finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
    private static List<Registration> race(ApplicationReleaseRegistrationService service, String first, String second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(key -> workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.register("aura-edu", key, content("b"));
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
