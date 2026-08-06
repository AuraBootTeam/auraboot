package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.exception.IdempotentException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real-stack coverage IT for {@link IdempotencyServiceImpl} — checkIdempotency (blank id / unknown
 * key -> null; known key -> replayed outcome), recordOutcome (insert + blank-id no-op), and
 * cleanupExpired. Dedicated synthetic tenant; raw teardown.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("IdempotencyServiceImpl Coverage IT — check/record/cleanup")
class IdempotencyServiceImplCoverageIT {

    private static final long TENANT_ID = 991_800_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private IdempotencyService idempotencyService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 991_800_002L, "idem-test-pid", "idem-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_idempotency_record WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("check returns null for blank/unknown; record then check replays the outcome")
    void checkRecordReplay() {
        assertNull(idempotencyService.checkIdempotency(null, TENANT_ID));
        assertNull(idempotencyService.checkIdempotency("", TENANT_ID));

        String reqId = "idem_" + seq.incrementAndGet();
        assertNull(idempotencyService.checkIdempotency(reqId, TENANT_ID));

        idempotencyService.recordOutcome(reqId, "demo:create",
                Map.of("name", "widget"), Map.of("status", "OK", "pid", "p1"), TENANT_ID);
        // blank-id record is a no-op
        idempotencyService.recordOutcome("", "demo:create", Map.of(), Map.of(), TENANT_ID);

        Map<String, Object> replayed = idempotencyService.checkIdempotency(reqId, TENANT_ID);
        assertNotNull(replayed);
        assertEquals("OK", String.valueOf(replayed.get("status")));
    }

    @Test
    @DisplayName("cleanupExpired runs and returns a non-negative count")
    void cleanupExpired() {
        assertTrue(idempotencyService.cleanupExpired() >= 0);
    }

    @Test
    @DisplayName("scoped replay requires the same operation and canonical payload")
    void scopedReplayConflictsOnChangedIntentAndSeparatesOperations() {
        String reqId = "scoped_" + seq.incrementAndGet();
        Map<String, Object> firstPayload = Map.of(
                "nested", Map.of("b", 2, "a", 1),
                "name", "widget");
        inTransaction(() -> {
            assertNull(idempotencyService.claimScopedIdempotency(
                    reqId, "demo:release", firstPayload, TENANT_ID));
            idempotencyService.recordScopedOutcome(
                    reqId, "demo:release", firstPayload, Map.of("pid", "r1"), TENANT_ID);
            return null;
        });

        Map<String, Object> replayed = idempotencyService.checkScopedIdempotency(
                reqId,
                "demo:release",
                Map.of("name", "widget", "nested", Map.of("a", 1, "b", 2)),
                TENANT_ID);
        assertEquals("r1", String.valueOf(replayed.get("pid")));
        assertThrows(IdempotentException.class, () -> idempotencyService.checkScopedIdempotency(
                reqId, "demo:release", Map.of("name", "changed"), TENANT_ID));
        assertNull(idempotencyService.checkScopedIdempotency(
                reqId, "demo:other", firstPayload, TENANT_ID));
    }

    @Test
    @DisplayName("two concurrent claims execute once and the loser replays the committed outcome")
    void concurrentSameIntentHasExactlyOneOwner() throws Exception {
        String reqId = "parallel_" + seq.incrementAndGet();
        Map<String, Object> intent = Map.of("target", "REQ-1", "expectedVersion", 7);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger owners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = pool.submit(() -> inTransaction(() -> {
                Map<String, Object> replay = idempotencyService.claimScopedIdempotency(
                        reqId, "demo:confirm", intent, TENANT_ID);
                if (replay != null) {
                    return replay;
                }
                owners.incrementAndGet();
                firstClaimed.countDown();
                await(releaseFirst);
                Map<String, Object> outcome = Map.of("pid", "REV-1");
                idempotencyService.recordScopedOutcome(
                        reqId, "demo:confirm", intent, outcome, TENANT_ID);
                return outcome;
            }));

            assertTrue(firstClaimed.await(10, TimeUnit.SECONDS));
            Future<Map<String, Object>> second = pool.submit(() -> inTransaction(() -> {
                Map<String, Object> replay = idempotencyService.claimScopedIdempotency(
                        reqId, "demo:confirm", intent, TENANT_ID);
                if (replay != null) {
                    return replay;
                }
                owners.incrementAndGet();
                Map<String, Object> outcome = Map.of("pid", "REV-2");
                idempotencyService.recordScopedOutcome(
                        reqId, "demo:confirm", intent, outcome, TENANT_ID);
                return outcome;
            }));

            releaseFirst.countDown();
            assertEquals("REV-1", first.get(10, TimeUnit.SECONDS).get("pid"));
            assertEquals("REV-1", second.get(10, TimeUnit.SECONDS).get("pid"));
            assertEquals(1, owners.get());
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a failed owner rolls back its claim and the same request can retry")
    void rolledBackClaimDoesNotPoisonRetry() {
        String reqId = "rollback_" + seq.incrementAndGet();
        Map<String, Object> intent = Map.of("target", "REQ-2");

        assertThrows(IllegalStateException.class, () -> inTransaction(() -> {
            assertNull(idempotencyService.claimScopedIdempotency(
                    reqId, "demo:confirm", intent, TENANT_ID));
            throw new IllegalStateException("simulated handler failure");
        }));

        Map<String, Object> outcome = inTransaction(() -> {
            assertNull(idempotencyService.claimScopedIdempotency(
                    reqId, "demo:confirm", intent, TENANT_ID));
            Map<String, Object> completed = Map.of("pid", "REV-RETRY");
            idempotencyService.recordScopedOutcome(
                    reqId, "demo:confirm", intent, completed, TENANT_ID);
            return completed;
        });
        assertEquals("REV-RETRY", outcome.get("pid"));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent idempotency test");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while testing idempotency", e);
        }
    }
}
