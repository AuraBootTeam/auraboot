package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link OutboxWorkerImpl} — pollAndDispatch claims a ready event and,
 * when its event_type can't be deserialized, increments its retry counter (the error/retry branch);
 * cleanupDelivered removes old delivered events. Events are seeded directly into ab_outbox.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.context.event.RecordApplicationEvents
@DisplayName("OutboxWorkerImpl Coverage IT — poll/retry + cleanup")
class OutboxWorkerImplCoverageIT {

    private static final long TENANT_ID = 991_900_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private OutboxWorkerImpl outboxWorker;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private com.auraboot.framework.meta.service.OutboxWriter outboxWriter;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired
    private org.springframework.test.context.event.ApplicationEvents publishedEvents;

    // This IT measures explicit worker invocations. Prevent a background timer from
    // consuming the same committed fixtures; the worker, mapper and fenced state service stay real.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.auraboot.framework.scheduler.service.impl.DatabaseSchedulerEngine scheduler;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 991_900_002L, "outbox-test-pid", "outbox-test-user");
        jdbcTemplate.update("DELETE FROM ab_outbox WHERE tenant_id = ?", TENANT_ID);
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_outbox WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private String seedPendingBad() {
        String eventId = "obx_bad_" + seq.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO ab_outbox (tenant_id, status, event_type, command_code, payload, event_id, "
                        + "retry_count, max_retries, next_retry_at, created_at) "
                        + "VALUES (?, 'pending', ?, 'demo:cmd', '{}', ?, 0, 3, ?, ?)",
                TENANT_ID, "com.auraboot.framework.NoSuchDomainEvent", eventId,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)),
                Timestamp.from(Instant.now()));
        return eventId;
    }

    @Test
    @DisplayName("pollAndDispatch claims a ready event and increments retry when it cannot deserialize")
    void pollRetriesUndeliverable() {
        var eventIds = new java.util.ArrayList<String>();
        for (int index = 0; index < 501; index++) eventIds.add(seedPendingBad());
        outboxWorker.pollAndDispatch();
        int touched = 0;
        for (String eventId : eventIds) {
            var state = jdbcTemplate.queryForMap(
                    "SELECT status,retry_count,last_error,lease_token FROM ab_outbox WHERE tenant_id=? AND event_id=?",
                    TENANT_ID, eventId);
            int retries = ((Number) state.get("retry_count")).intValue();
            if (retries > 0) {
                touched++;
                assertTrue(state.get("last_error") != null);
                assertTrue(state.get("lease_token") == null, "failed source lease must be released");
                assertTrue(!"processing".equals(state.get("status")));
            }
        }
        assertTrue(touched >= 101, "a full source batch must not impose another scheduling interval; touched=" + touched);
        assertTrue(touched <= 400, "one poll must yield with remaining backlog; touched=" + touched);
        assertTrue(touched < eventIds.size(), "a continuously full queue must remain bounded");
    }


    @Test
    @DisplayName("legacy writer preserves event identity and delivers its payload once")
    void legacyWriterDeliversOriginalEventExactlyOnce() {
        String command = "test:legacy_outbox_compatibility";
        var event = new com.auraboot.framework.meta.event.CommandExecutedEvent(
            command, "outbox_compatibility_probe", java.util.Map.of("orderReference", "LEGACY-001"),
            java.util.Map.of("status", "acknowledged"), TENANT_ID, 991_900_002L);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(
            status -> outboxWriter.write(event, command, TENANT_ID));
        var pending = jdbcTemplate.queryForMap(
            "SELECT status, schema_version FROM ab_outbox WHERE tenant_id=? AND event_id=?", TENANT_ID, event.getEventId());
        assertEquals("pending", pending.get("status"));
        org.junit.jupiter.api.Assertions.assertNull(pending.get("schema_version"));

        outboxWorker.pollAndDispatch();
        var state = jdbcTemplate.queryForMap(
            "SELECT status, retry_count, lease_token, lease_until, delivered_at FROM ab_outbox WHERE tenant_id=? AND event_id=?",
            TENANT_ID, event.getEventId());
        assertEquals("delivered", state.get("status"));
        assertEquals(0, ((Number) state.get("retry_count")).intValue());
        org.junit.jupiter.api.Assertions.assertNull(state.get("lease_token"));
        org.junit.jupiter.api.Assertions.assertNull(state.get("lease_until"));
        org.junit.jupiter.api.Assertions.assertNotNull(state.get("delivered_at"));
        var delivered = publishedEvents.stream(com.auraboot.framework.meta.event.CommandExecutedEvent.class)
            .filter(candidate -> command.equals(candidate.getCommandCode())).toList();
        assertEquals(1, delivered.size());
        assertEquals(event.getEventId(), delivered.getFirst().getEventId());
        assertEquals(event.getTenantId(), delivered.getFirst().getTenantId());
        assertEquals(event.getPayload(), delivered.getFirst().getPayload());
        assertEquals(event.getResult(), delivered.getFirst().getResult());
        outboxWorker.pollAndDispatch();
        assertEquals(1L, publishedEvents.stream(com.auraboot.framework.meta.event.CommandExecutedEvent.class)
            .filter(candidate -> command.equals(candidate.getCommandCode())).count());
    }

    @Test
    @DisplayName("cleanupDelivered removes old delivered events")
    void cleanupRemovesOldDelivered() {
        String eventId = "obx_done_" + seq.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO ab_outbox (tenant_id, status, event_type, command_code, payload, event_id, "
                        + "retry_count, max_retries, created_at, delivered_at) "
                        + "VALUES (?, 'delivered', 'x', 'demo:cmd', '{}', ?, 0, 3, ?, ?)",
                TENANT_ID, eventId,
                Timestamp.from(Instant.now().minus(100, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().minus(100, ChronoUnit.DAYS)));

        outboxWorker.cleanupDelivered();

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ab_outbox WHERE tenant_id = ? AND event_id = ?",
                Integer.class, TENANT_ID, eventId);
        assertEquals(0, remaining);
    }
}
