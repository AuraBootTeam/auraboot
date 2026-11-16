package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.openplatform.mapper.ExternalIngressDefaultsMapper;
import com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual production normalizer and MyBatis mapper against this task's migrated PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = "INGRESS_DEFAULTS_POSTGRES", matches = "true")
class ExternalIngressDefaultsPostgresIT {
    private DataSource datasource;
    private ExternalIngressDefaultsMapper records;
    private TransactionTemplate tx;
    private InstalledIntegrationRegistry registry;
    private final ObjectMapper json = new ObjectMapper();
    private long installation;
    private final long tenant = 16112026L;
    private static final String SOURCE = "test-ingress";

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL"); assertNotNull(url);
        var uri = java.net.URI.create(url.substring(5)); assertEquals("127.0.0.1", uri.getHost()); assertEquals("/auraboot_5", uri.getPath());
        var ds = new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"));
        datasource = ds;
        // Fixture setup only: business persistence below always uses the production mapper/service.
        try (var connection = datasource.getConnection()) {
            String pid = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
            long app;
            try (var stmt = connection.prepareStatement("INSERT INTO ab_external_application(pid, owner_tenant_id, name) VALUES (?, ?, 'Ingress defaults IT') RETURNING id")) {
                stmt.setString(1, pid); stmt.setLong(2, tenant);
                try (var rs = stmt.executeQuery()) { assertTrue(rs.next()); app = rs.getLong(1); }
            }
            try (var stmt = connection.prepareStatement("INSERT INTO ab_application_installation(pid, tenant_id, application_id, environment) VALUES (?, ?, ?, 'development') RETURNING id")) {
                stmt.setString(1, pid); stmt.setLong(2, tenant); stmt.setLong(3, app);
                try (var rs = stmt.executeQuery()) { assertTrue(rs.next()); installation = rs.getLong(1); }
            }
        }
        Configuration config = new Configuration(new Environment("owned-host", new SpringManagedTransactionFactory(), datasource));
        config.setMapUnderscoreToCamelCase(true); config.addMapper(ExternalIngressDefaultsMapper.class);
        records = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(ExternalIngressDefaultsMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        registry = mock(InstalledIntegrationRegistry.class);
        when(registry.requestNormalizer(SOURCE)).thenReturn(Optional.of((request, defaults) -> {
            var envelope = new LinkedHashMap<>(request);
            envelope.putIfAbsent("id", defaults.eventId()); envelope.putIfAbsent("occurredAt", defaults.receivedAt().toString());
            var identities = new LinkedHashMap<String, String>();
            if (request.get("id") instanceof String id) identities.put("event", id);
            if (request.get("operationId") instanceof String op) identities.put("operation", op);
            return new PreparedRequest(envelope, identities, List.of());
        }));
    }
    private ExternalIngressNormalizer service() { return new ExternalIngressNormalizer(registry, records, new OpenPlatformSecretCodec(), json); }
    private String accept(String header, String body) { return tx.execute(status -> service().normalize(tenant, installation, SOURCE, header, body)); }
    private int count() throws Exception {
        try (var connection = datasource.getConnection(); var stmt = connection.prepareStatement("SELECT count(*) FROM ab_external_ingress_defaults WHERE tenant_id=? AND installation_id=?")) {
            stmt.setLong(1, tenant); stmt.setLong(2, installation);
            try (var rs = stmt.executeQuery()) { assertTrue(rs.next()); return rs.getInt(1); }
        }
    }
    @Test void defaultsSurviveNewServiceInstanceAndJsonFormatting() throws Exception {
        String first = accept("stable-key-001", "{\"data\":{\"b\":2,\"a\":1}}");
        String replay = accept("stable-key-001", "{ \"data\": {\"a\":1,\"b\":2} }");
        assertEquals(json.readTree(first), json.readTree(replay));
        assertNotNull(json.readTree(first).get("id")); assertNotNull(json.readTree(first).get("occurredAt")); assertEquals(1, count());
    }
    @Test void sameKeyChangedPayloadIsConflictAndDoesNotOverwrite() throws Exception {
        String first = accept("stable-key-002", "{\"data\":1}");
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class, () -> accept("stable-key-002", "{\"data\":2}"));
        assertEquals(json.readTree(first), json.readTree(accept("stable-key-002", "{\"data\":1}"))); assertEquals(1, count());
    }
    @Test void newHeaderForSameEventStillUsesFirstDefaults() throws Exception {
        String body = "{\"id\":\"stable-event\",\"data\":1}";
        assertEquals(json.readTree(accept("header-0001", body)), json.readTree(accept("header-0002", body))); assertEquals(3, count());
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class, () -> accept("header-0003", body.replace(":1", ":2")));
        assertEquals(3, count());
    }
    @Test void operationIdentityDeduplicatesWithoutHeader() throws Exception {
        String body = "{\"operationId\":\"delete-op\",\"data\":1}";
        assertEquals(json.readTree(accept(null, body)), json.readTree(accept(null, body))); assertEquals(1, count());
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class, () -> accept(null, body.replace(":1", ":2")));
    }
    @Test void noStableIdentityMeansNewRequestEachTime() throws Exception {
        var first = json.readTree(accept(null, "{\"data\":1}")); var second = json.readTree(accept(null, "{\"data\":1}"));
        assertNotEquals(first.get("id"), second.get("id")); assertEquals(0, count());
    }
    @Test void downstreamFailureRollsBackAllDefaultAliases() throws Exception {
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            service().normalize(tenant, installation, SOURCE, "rollback-key", "{\"id\":\"rollback-event\"}");
            throw new IllegalStateException("Simulated downstream acceptance failure");
        }));
        assertEquals(0, count()); assertNotNull(accept("rollback-key", "{\"id\":\"rollback-event\"}")); assertEquals(2, count());
    }
    @Test void concurrentSameHeaderHasExactlyOneGeneratedIdentity() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1); var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) results.add(executor.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return accept("concurrent-key", "{\"data\":1}"); }));
            start.countDown(); var identities = new HashSet<String>();
            for (var result : results) identities.add(json.readTree(result.get(15, TimeUnit.SECONDS)).get("id").asText());
            assertEquals(1, identities.size()); assertEquals(1, count());
        }
    }
    @Test void crossTenantReadIsDeniedByMapperScope() {
        accept("tenant-key", "{\"data\":1}");
        assertNull(records.find(tenant + 1, installation, SOURCE, "header", new OpenPlatformSecretCodec().sha256("tenant-key")));
    }
    @Test void malformedJsonCannotClaimIdentity() throws Exception {
        for (String body : List.of("[]", "{", "{} {}", "{\"id\":1,\"id\":2}"))
            assertThrows(ExternalEventIngressService.InvalidExternalEventException.class, () -> accept("malformed-key", body));
        assertEquals(0, count());
    }
}
