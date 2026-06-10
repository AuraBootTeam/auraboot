package com.auraboot.framework.billing.metering;

import com.auraboot.framework.billing.metering.mapper.UsageDedupeConflictMapper;
import com.auraboot.framework.billing.metering.mapper.UsageEventMapper;
import com.auraboot.framework.billing.metering.model.DedupeStatus;
import com.auraboot.framework.billing.metering.model.RatingStatus;
import com.auraboot.framework.billing.metering.model.UsageDedupeConflict;
import com.auraboot.framework.billing.metering.model.UsageEvent;
import com.auraboot.framework.billing.metering.spi.MeteringResult;
import com.auraboot.framework.billing.metering.spi.MeteringResultStatus;
import com.auraboot.framework.billing.metering.spi.MeteringService;
import com.auraboot.framework.billing.metering.spi.UsageEventRequest;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link MeteringService} — verifies idempotent record(),
 * DUPLICATE_IGNORED, CONFLICT, REJECTED, and concurrent dedup correctness.
 *
 * <p>Runs against the real {@code aura_boot} test database.
 * All 3 billing migrations must be applied before running:
 * <pre>
 *   psql -h localhost -d aura_boot -f platform/src/main/resources/database/migrations/2026-06-10-billing-resource-catalog.sql
 *   psql -h localhost -d aura_boot -f platform/src/main/resources/database/migrations/2026-06-10-billing-quota.sql
 *   psql -h localhost -d aura_boot -f platform/src/main/resources/database/migrations/2026-06-10-billing-metering.sql
 * </pre>
 *
 * <p>Tests do NOT extend {@code @Transactional} at class level — the concurrent test
 * needs real committed rows visible across threads.  Cleanup is done in {@code @AfterEach}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MeteringServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MeteringService meteringService;

    @Autowired
    private UsageEventMapper usageEventMapper;

    @Autowired
    private UsageDedupeConflictMapper conflictMapper;

    // ── test isolation ────────────────────────────────────────────────────────

    /** All test events created in this class use this account */
    private static final Long TEST_ACCOUNT_ID = 800_000_001L;
    private static final String RESOURCE_CODE = "AI_TOKEN";
    private static final String SOURCE_SERVICE = "test-metering-it";

    /** Tracks idempotency keys created per test so cleanup is precise */
    private final List<String> usedIdempotencyKeys = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!usedIdempotencyKeys.isEmpty()) {
            // Delete conflicts first (no FK, but keep order for clarity)
            conflictMapper.delete(
                    new LambdaQueryWrapper<UsageDedupeConflict>()
                            .eq(UsageDedupeConflict::getSourceService, SOURCE_SERVICE)
            );
            // Delete usage events
            usageEventMapper.delete(
                    new LambdaQueryWrapper<UsageEvent>()
                            .eq(UsageEvent::getAccountId, TEST_ACCOUNT_ID)
                            .eq(UsageEvent::getSourceService, SOURCE_SERVICE)
            );
            usedIdempotencyKeys.clear();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UsageEventRequest baseRequest(String idempotencyKey) {
        usedIdempotencyKeys.add(idempotencyKey);
        return UsageEventRequest.builder()
                .idempotencyKey(idempotencyKey)
                .accountId(TEST_ACCOUNT_ID)
                .resourceCode(RESOURCE_CODE)
                .quantity(new BigDecimal("100"))
                .unit("TOKEN")
                .occurredAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .sourceService(SOURCE_SERVICE)
                .build();
    }

    private String randomKey() {
        return "idem-" + UUID.randomUUID().toString().replace("-", "");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 1: ACCEPTED — new event recorded with UNIQUE + PENDING
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("record new event → ACCEPTED with dedupe_status=UNIQUE, rating_status=PENDING")
    void recordNewEvent_Accepted() {
        String key = randomKey();
        UsageEventRequest req = baseRequest(key);

        MeteringResult result = meteringService.record(req);

        assertThat(result.getStatus()).isEqualTo(MeteringResultStatus.ACCEPTED);
        assertThat(result.getEventCode()).isNotBlank().startsWith("UE-");

        // Verify persisted state
        Optional<UsageEvent> found = meteringService.findByCode(result.getEventCode());
        assertThat(found).isPresent();
        UsageEvent event = found.get();
        assertThat(event.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
        assertThat(event.getResourceCode()).isEqualTo(RESOURCE_CODE);
        assertThat(event.getQuantity()).isEqualByComparingTo("100");
        assertThat(event.getDedupeStatus()).isEqualTo(DedupeStatus.UNIQUE.name());
        assertThat(event.getRatingStatus()).isEqualTo(RatingStatus.PENDING.name());
        assertThat(event.getReceivedAt()).isNotNull();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 2: DUPLICATE_IGNORED — same key + same payload → original event_code returned, 1 row
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("record same key + same payload twice → DUPLICATE_IGNORED, usage_event count stays 1")
    void recordDuplicate_SamePayload_Ignored() {
        String key = randomKey();
        UsageEventRequest req = baseRequest(key);

        MeteringResult first  = meteringService.record(req);
        MeteringResult second = meteringService.record(req);  // identical re-submission

        assertThat(first.getStatus()).isEqualTo(MeteringResultStatus.ACCEPTED);
        assertThat(second.getStatus()).isEqualTo(MeteringResultStatus.DUPLICATE_IGNORED);
        assertThat(second.getEventCode()).isEqualTo(first.getEventCode());

        // Only one row should exist
        long rowCount = usageEventMapper.selectCount(
                new LambdaQueryWrapper<UsageEvent>()
                        .eq(UsageEvent::getSourceService, SOURCE_SERVICE)
                        .eq(UsageEvent::getIdempotencyKey, key)
        );
        assertThat(rowCount).isEqualTo(1);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 3: CONFLICT — same key, different payload → conflict table, no new usage_event
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("record same key + different qty → CONFLICT, written to conflict table, usage_event NOT incremented")
    void recordConflict_DifferentPayload_LoggedNotCharged() {
        String key = randomKey();
        UsageEventRequest original = baseRequest(key);

        // Different quantity = payload mismatch
        UsageEventRequest conflict = UsageEventRequest.builder()
                .idempotencyKey(key)
                .accountId(TEST_ACCOUNT_ID)
                .resourceCode(RESOURCE_CODE)
                .quantity(new BigDecimal("999"))   // different!
                .unit("TOKEN")
                .occurredAt(original.getOccurredAt())  // same occurredAt
                .sourceService(SOURCE_SERVICE)
                .build();
        usedIdempotencyKeys.add(key);  // add once (already added by original)

        MeteringResult first  = meteringService.record(original);
        MeteringResult second = meteringService.record(conflict);

        assertThat(first.getStatus()).isEqualTo(MeteringResultStatus.ACCEPTED);
        assertThat(second.getStatus()).isEqualTo(MeteringResultStatus.CONFLICT);
        // Existing event_code is returned for reference
        assertThat(second.getEventCode()).isEqualTo(first.getEventCode());

        // usage_event still has exactly 1 row for this key
        long eventCount = usageEventMapper.selectCount(
                new LambdaQueryWrapper<UsageEvent>()
                        .eq(UsageEvent::getSourceService, SOURCE_SERVICE)
                        .eq(UsageEvent::getIdempotencyKey, key)
        );
        assertThat(eventCount).isEqualTo(1);

        // Conflict row must be written
        long conflictCount = conflictMapper.selectCount(
                new LambdaQueryWrapper<UsageDedupeConflict>()
                        .eq(UsageDedupeConflict::getSourceService, SOURCE_SERVICE)
                        .eq(UsageDedupeConflict::getIdempotencyKey, key)
        );
        assertThat(conflictCount).isGreaterThanOrEqualTo(1);

        // Verify conflict row references the original event
        UsageDedupeConflict conflictRow = conflictMapper.selectOne(
                new LambdaQueryWrapper<UsageDedupeConflict>()
                        .eq(UsageDedupeConflict::getSourceService, SOURCE_SERVICE)
                        .eq(UsageDedupeConflict::getIdempotencyKey, key)
        );
        assertThat(conflictRow.getExistingEventCode()).isEqualTo(first.getEventCode());
        assertThat(conflictRow.getConflictingPayloadJson()).contains("999");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 4: REJECTED — unregistered resource_code
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("record with unregistered resource_code → REJECTED, nothing written to DB")
    void recordUnknownResource_Rejected() {
        String key = randomKey();
        usedIdempotencyKeys.add(key);

        UsageEventRequest req = UsageEventRequest.builder()
                .idempotencyKey(key)
                .accountId(TEST_ACCOUNT_ID)
                .resourceCode("NOT_A_REAL_RESOURCE_XYZ")
                .quantity(new BigDecimal("1"))
                .unit("COUNT")
                .occurredAt(Instant.now())
                .sourceService(SOURCE_SERVICE)
                .build();

        MeteringResult result = meteringService.record(req);

        assertThat(result.getStatus()).isEqualTo(MeteringResultStatus.REJECTED);
        assertThat(result.getEventCode()).isNull();
        assertThat(result.getReason()).containsIgnoringCase("RESOURCE_NOT_REGISTERED");

        // Nothing written to usage_event
        long count = usageEventMapper.selectCount(
                new LambdaQueryWrapper<UsageEvent>()
                        .eq(UsageEvent::getSourceService, SOURCE_SERVICE)
                        .eq(UsageEvent::getIdempotencyKey, key)
        );
        assertThat(count).isZero();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 5: REJECTED — missing required fields
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("record with missing idempotencyKey → REJECTED")
    void recordMissingIdempotencyKey_Rejected() {
        UsageEventRequest req = UsageEventRequest.builder()
                // no idempotencyKey
                .accountId(TEST_ACCOUNT_ID)
                .resourceCode(RESOURCE_CODE)
                .quantity(new BigDecimal("50"))
                .unit("TOKEN")
                .occurredAt(Instant.now())
                .sourceService(SOURCE_SERVICE)
                .build();

        MeteringResult result = meteringService.record(req);

        assertThat(result.getStatus()).isEqualTo(MeteringResultStatus.REJECTED);
        assertThat(result.getReason()).containsIgnoringCase("idempotencyKey");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 6: listByAccount query
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("listByAccount returns events for the account ordered by occurred_at DESC")
    void listByAccount_ReturnsCorrectEvents() {
        // Insert 3 events with different occurred_at
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        for (int i = 0; i < 3; i++) {
            String key = randomKey();
            UsageEventRequest req = UsageEventRequest.builder()
                    .idempotencyKey(key)
                    .accountId(TEST_ACCOUNT_ID)
                    .resourceCode(RESOURCE_CODE)
                    .quantity(new BigDecimal(String.valueOf((i + 1) * 10)))
                    .unit("TOKEN")
                    .occurredAt(base.minusSeconds(i * 60))
                    .sourceService(SOURCE_SERVICE)
                    .build();
            usedIdempotencyKeys.add(key);
            meteringService.record(req);
        }

        List<UsageEvent> events = meteringService.listByAccount(TEST_ACCOUNT_ID, RESOURCE_CODE, 10);
        assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        // Verify descending order
        for (int i = 0; i < events.size() - 1; i++) {
            assertThat(events.get(i).getOccurredAt())
                    .isAfterOrEqualTo(events.get(i + 1).getOccurredAt());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 7: Concurrent same-key submissions — no double-write
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("concurrent record() with same key — DB UNIQUE constraint prevents double-write")
    void concurrentSameKey_NoDoubleWrite() throws Exception {
        String key = randomKey();
        usedIdempotencyKeys.add(key);

        UsageEventRequest req = UsageEventRequest.builder()
                .idempotencyKey(key)
                .accountId(TEST_ACCOUNT_ID)
                .resourceCode(RESOURCE_CODE)
                .quantity(new BigDecimal("500"))
                .unit("TOKEN")
                .occurredAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .sourceService(SOURCE_SERVICE)
                .build();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger accepted  = new AtomicInteger(0);
        AtomicInteger nonAccepted = new AtomicInteger(0);
        List<Future<MeteringResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> meteringService.record(req)));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        for (Future<MeteringResult> f : futures) {
            MeteringResult r = f.get();
            if (r.getStatus() == MeteringResultStatus.ACCEPTED) {
                accepted.incrementAndGet();
            } else {
                nonAccepted.incrementAndGet();
            }
        }

        // Exactly 1 ACCEPTED; the rest are DUPLICATE_IGNORED (or CONFLICT if race on null re-query)
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(nonAccepted.get()).isEqualTo(threads - 1);

        // Exactly 1 row in the DB
        long rowCount = usageEventMapper.selectCount(
                new LambdaQueryWrapper<UsageEvent>()
                        .eq(UsageEvent::getSourceService, SOURCE_SERVICE)
                        .eq(UsageEvent::getIdempotencyKey, key)
        );
        assertThat(rowCount).isEqualTo(1);
    }
}
