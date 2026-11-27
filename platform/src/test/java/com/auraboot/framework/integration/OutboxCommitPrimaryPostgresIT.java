package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.event.context.ThreadContextScopes;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.meta.service.impl.OutboxWorkerImpl;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** Real production queue/transaction/listener/worker seam; no scheduler, HTTP, PF4J or native ERP qualification. */
@EnabledIfEnvironmentVariable(named = "OUTBOX_COMMIT_PRIMARY_PG_IT", matches = "true")
class OutboxCommitPrimaryPostgresIT {
    private static final long TENANT = 377065346345078784L;
    private static final String TYPE = "test.procurement.commit-primary.v1";
    private static final AtomicInteger SCHEMAS = new AtomicInteger();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private JdbcTemplate publicJdbc;
    private DataSourceTransactionManager transactions;
    private SqlSessionTemplate sessions;
    private SimpleMeterRegistry meters;
    private ReliableIntegrationAccessorImpl accessor;
    private OutboxWorkerImpl worker;
    private ThreadPoolTaskExecutor executor;
    private String schema;
    private final Map<String, String> publicQueueHashes = new LinkedHashMap<>();
    private final AtomicBoolean failNextEffect = new AtomicBoolean();

    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void prepareOwnedIsolatedQueue() throws Exception {
        assertEquals("procurement-scope-gaps", System.getenv("AURA_RUNTIME_NAME"));
        String url = System.getenv("TEST_DATABASE_URL");
        assertNotNull(url); assertTrue(url.startsWith("jdbc:postgresql://"));
        URI target = URI.create(url.substring(5));
        assertEquals("127.0.0.1", target.getHost()); assertTrue(target.getPort() > 0);
        assertEquals("/enterprise_1", target.getPath());
        assertTrue(target.getQuery() == null || "charSet=UTF8".equals(target.getQuery()), "Only the owned runtime charset option is allowed");
        assertTrue(target.getUserInfo() == null, "Credentials must be supplied separately");
        String prefix = System.getenv("OUTBOX_COMMIT_PRIMARY_SCHEMA_PREFIX");
        assertNotNull(prefix); assertTrue(prefix.matches("it_procurement_commit_[a-f0-9]{16}"));
        schema = prefix + "_" + SCHEMAS.incrementAndGet();
        String username = System.getenv("TEST_DATABASE_USERNAME");
        String password = System.getenv("TEST_DATABASE_PASSWORD");
        assertNotNull(username); assertNotNull(password);
        publicJdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        for (String table : List.of("ab_outbox", "ab_integration_receipt", "ab_integration_dead_letter")) {
            publicQueueHashes.put(table, publicQueueHash(table));
        }
        // Names come only from the guarded prefix and fixed table roster. Existing schemas are never reused or dropped.
        publicJdbc.execute("CREATE SCHEMA " + schema);
        for (String table : List.of("ab_outbox", "ab_integration_receipt", "ab_integration_dead_letter")) {
            publicJdbc.execute("CREATE TABLE " + schema + "." + table + " (LIKE public." + table + " INCLUDING ALL)");
            var id = publicJdbc.queryForMap("SELECT column_default,is_identity FROM information_schema.columns "
                    + "WHERE table_schema=? AND table_name=? AND column_name='id'", schema, table);
            // LIKE copies serial defaults by reference; use a private identity before the first fixture insert.
            if ("NO".equals(id.get("is_identity")) && String.valueOf(id.get("column_default")).startsWith("nextval(")) {
                publicJdbc.execute("ALTER TABLE " + schema + "." + table + " ALTER COLUMN id DROP DEFAULT");
                publicJdbc.execute("ALTER TABLE " + schema + "." + table + " ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY");
            }
            String sequence = publicJdbc.queryForObject("SELECT pg_get_serial_sequence(?, 'id')", String.class, schema + "." + table);
            assertNotNull(sequence); assertTrue(sequence.startsWith(schema + "."), "Fixture identities cannot advance public sequences");
        }
        var source = new DriverManagerDataSource(url + (target.getQuery() == null ? "?" : "&") + "currentSchema=" + schema, username, password);
        jdbc = new JdbcTemplate(source);
        assertEquals(schema, jdbc.queryForObject("SELECT current_schema()", String.class));
        jdbc.execute("CREATE TABLE dispatch_effect (event_id text PRIMARY KEY,tenant_id bigint NOT NULL, "
                + "thread_name text NOT NULL,correlation_id text NOT NULL)");
        transactions = new DataSourceTransactionManager(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OutboxEventMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        sessions = new SqlSessionTemplate(factory.getObject());
        meters = new SimpleMeterRegistry();
    }

    private void start(boolean listen) {
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, ReliableOutboxDispatchConfiguration.class);
        context.registerBean(DataSourceTransactionManager.class, () -> transactions);
        context.registerBean(OutboxEventMapper.class, () -> sessions.getMapper(OutboxEventMapper.class));
        context.registerBean(SimpleMeterRegistry.class, () -> meters);
        context.registerBean(ReliableIntegrationMetrics.class);
        context.registerBean(ReliableIntegrationClaimService.class);
        context.registerBean(ReliableIntegrationStateService.class);
        context.registerBean(ThreadContextScopes.class, () -> new ThreadContextScopes(List.of()));
        // Empty external-plugin discovery and unused legacy webhooks are boundaries, not queue/transaction substitutes.
        context.registerBean(AuraPluginManager.class, () -> {
            var plugins = mock(AuraPluginManager.class);
            when(plugins.getExtensionsOfType(ReliableEventConsumerExtension.class)).thenReturn(List.of());
            return plugins;
        });
        context.registerBean(WebhookDispatcher.class, () -> mock(WebhookDispatcher.class));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(ReliableEventConsumerExtension.class, () -> new ReliableEventConsumerExtension() {
            public String consumerCode() { return "test.procurement.commit-primary.consumer.v1"; }
            public Set<String> subscribedEventTypes() { return Set.of(TYPE); }
            public void consume(IntegrationEventEnvelope event) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(Long.valueOf(TENANT), MetaContext.getCurrentTenantId());
                assertEquals("integration-runtime", MetaContext.getCurrentUserPid());
                assertTrue(Thread.currentThread().getName().startsWith("reliable-outbox-"), "Effect cannot run on accepting thread");
                assertEquals(event.correlationId(), org.slf4j.MDC.get("requestId"));
                assertEquals(1, jdbc.update("INSERT INTO dispatch_effect VALUES (?,?,?,?)", event.eventId(),
                        event.tenantId(), Thread.currentThread().getName(), event.correlationId()));
                if (failNextEffect.getAndSet(false)) throw new IllegalStateException("Controlled transactional consumer failure");
            }
        });
        context.registerBean(ReliableEventConsumerRegistry.class);
        context.registerBean(ReliableEventDeliveryExecutor.class, () -> new ReliableEventDeliveryExecutor(
                context.getBean(OutboxEventMapper.class), context.getBean(ReliableIntegrationMetrics.class), transactions));
        context.registerBean(OutboxWorkerImpl.class);
        if (listen) context.registerBean(ReliableOutboxDispatchListener.class);
        context.refresh();
        assertEquals(0, context.getBeanNamesForType(SchedulerEngine.class).length);
        assertEquals(0, context.getBeanNamesForAnnotation(org.springframework.scheduling.annotation.EnableScheduling.class).length);
        executor = context.getBean("reliableOutboxDispatchExecutor", ThreadPoolTaskExecutor.class);
        worker = context.getBean(OutboxWorkerImpl.class);
        accessor = new ReliableIntegrationAccessorImpl(context.getBean(OutboxEventMapper.class),
                context.getBean(ObjectMapper.class), context.getBean(ReliableIntegrationMetrics.class), null, context);
    }

    @AfterEach void retainEvidenceAndClose() {
        if (context != null) context.close();
        if (meters != null) meters.close();
        publicQueueHashes.forEach((table, expected) -> assertEquals(expected, publicQueueHash(table),
                "Existing public queue facts changed: " + table));
        // Queue/effect rows and their private identities remain available for independent inspection.
    }

    private String publicQueueHash(String table) {
        String json = publicJdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]') "
                + "FROM public." + table + " t", String.class);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new AssertionError("Required SHA-256 is unavailable", unavailable);
        }
    }

    private IntegrationEventEnvelope event() {
        String id = "commit-primary-" + UUID.randomUUID();
        return new IntegrationEventEnvelope("1.0", id, TYPE, "test.procurement", "test/" + id,
                Instant.parse("2026-11-12T00:00:00Z"), TENANT, "request-" + id, null,
                "test/" + id, 0, Map.of("fixtureOnly", true), Map.of());
    }

    private double metric(String outcome) {
        return meters.get("auraboot_integration_events_total").tag("outcome", outcome).counter().count();
    }

    private void finishedDispatch() throws Exception {
        // FIFO barrier observes the production executor; it does not scan or dispatch the queue.
        executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    private void assertDelivered(IntegrationEventEnvelope event) {
        assertEquals("delivered", jdbc.queryForObject("SELECT status FROM ab_outbox WHERE event_id=?", String.class, event.eventId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_integration_receipt WHERE event_id=? AND status='applied'", Integer.class, event.eventId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dispatch_effect WHERE event_id=? AND tenant_id=?", Integer.class, event.eventId(), TENANT));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_integration_dead_letter", Integer.class));
        assertEquals(0, publicJdbc.queryForObject("SELECT count(*) FROM public.ab_outbox WHERE event_id=?", Integer.class, event.eventId()));
    }

    @Test void committedEventDeliversWithNoScheduledScanner() throws Exception {
        start(true);
        var event = event();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            accessor.enqueue(event);
            // A distinct connection cannot see an uncommitted queue row or effect.
            try (var connection = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT count(*) FROM ab_outbox WHERE event_id=?")) {
                statement.setString(1, event.eventId());
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            assertEquals(0, metric("enqueued")); assertEquals(0, metric("commit_dispatch_started"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dispatch_effect", Integer.class));
        });
        finishedDispatch();
        assertEquals(1, metric("enqueued")); assertEquals(1, metric("commit_dispatch_started"));
        assertEquals(1, metric("claimed")); assertEquals(1, metric("delivered"));
        assertDelivered(event);
    }

    @Test void rolledBackEventNeverBecomesVisibleOrStartsWork() throws Exception {
        start(true);
        var event = event();
        new TransactionTemplate(transactions).executeWithoutResult(status -> { accessor.enqueue(event); status.setRollbackOnly(); });
        finishedDispatch();
        assertEquals(0, metric("enqueued")); assertEquals(0, metric("commit_dispatch_started"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_outbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_integration_receipt", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dispatch_effect", Integer.class));
    }

    @Test void exactReplayDoesNotScheduleOrApplyAgain() throws Exception {
        start(true);
        var event = event();
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertTrue(accessor.enqueueIdempotent(event)));
        finishedDispatch(); assertDelivered(event);
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertFalse(accessor.enqueueIdempotent(event)));
        finishedDispatch(); assertDelivered(event);
        assertEquals(1, metric("enqueued")); assertEquals(1, metric("commit_dispatch_started"));
        assertEquals(1, metric("receipt_applied")); assertEquals(1, metric("delivered"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ab_outbox", Integer.class));
    }

    @Test void retainedQueueRecoversAfterAControlledMissingSignal() {
        start(false);
        var event = event();
        new TransactionTemplate(transactions).executeWithoutResult(status -> accessor.enqueue(event));
        assertEquals("pending", jdbc.queryForObject("SELECT status FROM ab_outbox WHERE event_id=?", String.class, event.eventId()));
        assertEquals(0, metric("commit_dispatch_started"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dispatch_effect", Integer.class));
        // Exercise the real recovery entry on its own worker thread, without a scheduled scanner or accepting transaction.
        try { executor.submit(worker::pollAndDispatch).get(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        assertDelivered(event); assertEquals(1, metric("delivered"));
        assertEquals(0, metric("commit_dispatch_started"));
    }

    @Test void conflictingReplayCannotReplaceTheCommittedEnvelope() throws Exception {
        start(true);
        var event = event();
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertTrue(accessor.enqueueIdempotent(event)));
        finishedDispatch(); assertDelivered(event);
        var changed = new IntegrationEventEnvelope(event.schemaVersion(), event.eventId(), event.eventType(), event.source(),
                event.subject(), event.occurredAt(), event.tenantId(), event.correlationId(), event.causationId(),
                event.orderingKey(), event.sequence(), Map.of("fixtureOnly", true, "changed", true), event.headers());
        var failure = assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> accessor.enqueueIdempotent(changed)));
        assertEquals("RELIABLE_ENVELOPE_CONFLICT", failure.getMessage());
        finishedDispatch(); assertDelivered(event);
        assertEquals(new ObjectMapper().readTree("{\"fixtureOnly\":true}"), new ObjectMapper().readTree(
                jdbc.queryForObject("SELECT payload::text FROM ab_outbox WHERE event_id=?", String.class, event.eventId())));
        assertEquals(1, metric("enqueued")); assertEquals(1, metric("commit_dispatch_started"));
        assertEquals(1, metric("receipt_applied")); assertEquals(1, metric("delivered"));
    }

    @Test void failedConsumerRollsBackEffectAndReceiptBeforeDueRecovery() throws Exception {
        start(true); failNextEffect.set(true);
        var event = event();
        new TransactionTemplate(transactions).executeWithoutResult(status -> accessor.enqueue(event));
        finishedDispatch();
        var state = jdbc.queryForMap("SELECT status,retry_count,last_error FROM ab_outbox WHERE event_id=?", event.eventId());
        assertEquals("pending", state.get("status")); assertEquals(1, state.get("retry_count"));
        assertEquals("Controlled transactional consumer failure", state.get("last_error"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dispatch_effect", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ab_integration_receipt", Integer.class));
        assertEquals(1, metric("retry_scheduled")); assertEquals(0, metric("delivered"));
        // Only observe the due time here: this wait does not execute a worker or a scheduled scan.
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT next_retry_at<=now() FROM ab_outbox WHERE event_id=?", Boolean.class, event.eventId())));
        executor.submit(worker::pollAndDispatch).get(10, TimeUnit.SECONDS);
        assertDelivered(event);
        assertEquals(1, metric("receipt_applied")); assertEquals(1, metric("delivered"));
        assertEquals(2, metric("claimed")); assertEquals(1, metric("commit_dispatch_started"));
    }

    @Test void saturatedSignalLeavesCommittedWorkForRecovery() throws Exception {
        start(true);
        var occupied = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocked = executor.submit(() -> {
            occupied.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test worker release timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        });
        assertTrue(occupied.await(5, TimeUnit.SECONDS));
        var queued = executor.submit(() -> { });
        var event = event();
        try {
            assertDoesNotThrow(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> accessor.enqueue(event)));
            assertEquals(1, metric("enqueued")); assertEquals(1, metric("commit_dispatch_deferred"));
            assertEquals(0, metric("commit_dispatch_started"));
            assertEquals("pending", jdbc.queryForObject("SELECT status FROM ab_outbox WHERE event_id=?", String.class, event.eventId()));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dispatch_effect", Integer.class));
        } finally { release.countDown(); }
        blocked.get(5, TimeUnit.SECONDS); queued.get(5, TimeUnit.SECONDS);
        executor.submit(worker::pollAndDispatch).get(10, TimeUnit.SECONDS);
        assertDelivered(event); assertEquals(1, metric("delivered"));
        assertEquals(0, metric("commit_dispatch_started"));
    }
}
