package com.auraboot.framework.billing.quota;

import com.auraboot.framework.billing.BillingAccountSeedHelper;
import com.auraboot.framework.billing.quota.mapper.*;
import com.auraboot.framework.billing.quota.model.*;
import com.auraboot.framework.billing.quota.spi.*;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link QuotaService}.
 *
 * <p>Runs against the real {@code aura_boot} test database.
 * The quota migration (2026-06-10-billing-quota.sql) must be applied before running:
 * {@code psql -h localhost -d aura_boot -f platform/src/main/resources/database/migrations/2026-06-10-billing-quota.sql}
 *
 * <p>These tests do NOT extend @Transactional at class level — bucket operations use
 * optimistic locking and the concurrent test needs real committed rows.
 * Cleanup is done in @AfterEach via direct mapper deletes.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuotaServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BillingAccountSeedHelper billingAccountSeedHelper;

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private QuotaPoolMapper quotaPoolMapper;

    @Autowired
    private QuotaBucketMapper quotaBucketMapper;

    @Autowired
    private QuotaReservationMapper reservationMapper;

    @Autowired
    private QuotaReservationLineMapper reservationLineMapper;

    @Autowired
    private QuotaLedgerMapper ledgerMapper;

    // ── test fixtures ─────────────────────────────────────────────────────────

    /** Fixed account ID for test isolation */
    private static final Long TEST_ACCOUNT_ID   = 900_000_001L;
    /** Fixed subscription ID */
    private static final Long TEST_SUBSCRIPTION = 900_000_002L;
    /** Resource used in all tests */
    private static final String RESOURCE_CODE   = "AI_TOKEN";

    private Long currentBucketId;
    private Long currentPoolId;

    @BeforeEach
    void createTestFixtures() {
        // Seed parent ab_billing_account row so FK on quota_pool/bucket is satisfied.
        // Task 2 FK-ized account_id → ab_billing_account(id); bare IDs now require a parent row.
        billingAccountSeedHelper.ensureAccountExists(TEST_ACCOUNT_ID, "ACC-IT-" + TEST_ACCOUNT_ID);

        // Create a pool
        QuotaPool pool = QuotaPool.builder()
                .poolCode("test-pool-" + UUID.randomUUID())
                .accountId(TEST_ACCOUNT_ID)
                .subscriptionId(TEST_SUBSCRIPTION)
                .resourceCode(RESOURCE_CODE)
                .scopeType(ScopeType.ACCOUNT.name())
                .poolType(PoolType.DEDICATED.name())
                .status(BucketStatus.ACTIVE.name())
                .build();
        quotaPoolMapper.insert(pool);
        currentPoolId = pool.getId();

        // Create a fresh bucket with 10 000 tokens
        QuotaBucket bucket = QuotaBucket.builder()
                .bucketCode("test-bucket-" + UUID.randomUUID())
                .poolId(currentPoolId)
                .accountId(TEST_ACCOUNT_ID)
                .subscriptionId(TEST_SUBSCRIPTION)
                .resourceCode(RESOURCE_CODE)
                .totalAmount(new BigDecimal("10000"))
                .usedAmount(BigDecimal.ZERO)
                .reservedAmount(BigDecimal.ZERO)
                .unit("TOKEN")
                .periodStart(Instant.now().minus(1, ChronoUnit.HOURS))
                .periodEnd(Instant.now().plus(30, ChronoUnit.DAYS))
                .sourceType(BucketSourceType.BASE_PLAN.name())
                .priority(100)
                .overagePolicy(OveragePolicy.HARD_LIMIT.name())
                .status(BucketStatus.ACTIVE.name())
                .version(0L)
                .build();
        quotaBucketMapper.insert(bucket);
        currentBucketId = bucket.getId();
    }

    @AfterEach
    void cleanupFixtures() {
        // Delete ledger, lines, reservations, bucket, pool in order
        ledgerMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuotaLedger>()
                        .eq(QuotaLedger::getAccountId, TEST_ACCOUNT_ID));
        reservationLineMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuotaReservationLine>()
                        .eq(QuotaReservationLine::getBucketId, currentBucketId));
        reservationMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuotaReservation>()
                        .eq(QuotaReservation::getAccountId, TEST_ACCOUNT_ID));
        quotaBucketMapper.deleteById(currentBucketId);
        quotaPoolMapper.deleteById(currentPoolId);
        // Remove the seeded billing account (parent of pool/bucket FK).
        billingAccountSeedHelper.removeAccount(TEST_ACCOUNT_ID);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private QuotaAuthorizeRequest buildRequest(BigDecimal qty, String idempotencyKey) {
        return QuotaAuthorizeRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .subscriptionId(TEST_SUBSCRIPTION)
                .resourceCode(RESOURCE_CODE)
                .estimatedQuantity(qty)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private QuotaBucket freshBucket() {
        return quotaBucketMapper.selectById(currentBucketId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 1: Reserve → Commit → verify used/reserved, no negatives
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("reserve 5000 → commit 4300 → bucket used=4300 reserved=0, no negatives")
    void reserveCommitHappyPath() {
        // Reserve 5000
        QuotaDecision decision = quotaService.authorize(
                buildRequest(new BigDecimal("5000"), "idem-happy-1"));

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getReservationCode()).isNotBlank();

        QuotaBucket afterReserve = freshBucket();
        assertThat(afterReserve.getReservedAmount())
                .isEqualByComparingTo("5000");
        assertThat(afterReserve.getUsedAmount())
                .isEqualByComparingTo("0");
        assertThat(afterReserve.availableAmount())
                .isEqualByComparingTo("5000");  // 10000 - 0 - 5000

        // Commit 4300 (< 5000 estimated)
        QuotaCommitResult result = quotaService.commit(
                decision.getReservationCode(), new BigDecimal("4300"));

        assertThat(result.getActualAmount()).isEqualByComparingTo("4300");
        assertThat(result.getReleasedDelta()).isEqualByComparingTo("700");

        QuotaBucket afterCommit = freshBucket();
        assertThat(afterCommit.getUsedAmount())
                .isEqualByComparingTo("4300");
        assertThat(afterCommit.getReservedAmount())
                .isEqualByComparingTo("0");
        // available = 10000 - 4300 - 0 = 5700
        assertThat(afterCommit.availableAmount())
                .isEqualByComparingTo("5700");

        // Non-negative invariants
        assertThat(afterCommit.getUsedAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(afterCommit.getReservedAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(afterCommit.availableAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 2: Reserve → Release → bucket restored
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("reserve 3000 → release → bucket reserved=0, available=10000 restored")
    void reserveRelease() {
        QuotaDecision decision = quotaService.authorize(
                buildRequest(new BigDecimal("3000"), "idem-release-1"));

        assertThat(decision.isAllowed()).isTrue();
        assertThat(freshBucket().getReservedAmount()).isEqualByComparingTo("3000");

        quotaService.release(decision.getReservationCode());

        QuotaBucket after = freshBucket();
        assertThat(after.getReservedAmount()).isEqualByComparingTo("0");
        assertThat(after.getUsedAmount()).isEqualByComparingTo("0");
        assertThat(after.availableAmount()).isEqualByComparingTo("10000");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 3: Hard limit DENY when insufficient
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("request 15000 > bucket 10000 → DENY INSUFFICIENT_QUOTA")
    void hardLimitDeny() {
        QuotaDecision decision = quotaService.authorize(
                buildRequest(new BigDecimal("15000"), "idem-deny-1"));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getDenyReason()).isEqualTo("INSUFFICIENT_QUOTA");

        // Bucket should be untouched
        QuotaBucket bucket = freshBucket();
        assertThat(bucket.getReservedAmount()).isEqualByComparingTo("0");
        assertThat(bucket.getUsedAmount()).isEqualByComparingTo("0");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 4: Unregistered resource → DENY
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("unregistered resource code → DENY RESOURCE_NOT_REGISTERED")
    void unregisteredResourceDenied() {
        QuotaAuthorizeRequest req = QuotaAuthorizeRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .subscriptionId(TEST_SUBSCRIPTION)
                .resourceCode("NONEXISTENT_RESOURCE_XYZ")
                .estimatedQuantity(new BigDecimal("100"))
                .idempotencyKey("idem-unregistered-1")
                .build();

        QuotaDecision decision = quotaService.authorize(req);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getDenyReason()).isEqualTo("RESOURCE_NOT_REGISTERED");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 5: Idempotency — same key → same reservation, no double deduct
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("duplicate authorize with same idempotency_key → returns existing, no double deduct")
    void idempotentAuthorize() {
        String idempotencyKey = "idem-dedup-" + UUID.randomUUID();
        QuotaAuthorizeRequest req = buildRequest(new BigDecimal("1000"), idempotencyKey);

        QuotaDecision first  = quotaService.authorize(req);
        QuotaDecision second = quotaService.authorize(req);

        assertThat(first.isAllowed()).isTrue();
        assertThat(second.isAllowed()).isTrue();
        // Same reservation returned
        assertThat(second.getReservationCode()).isEqualTo(first.getReservationCode());

        // Only 1000 reserved (not 2000)
        QuotaBucket bucket = freshBucket();
        assertThat(bucket.getReservedAmount()).isEqualByComparingTo("1000");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Test 6: Concurrent authorize — optimistic lock, no oversell
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("10 concurrent authorize(1500 each) against 10000 bucket → no oversell, no negatives")
    void concurrentAuthorizeNoOversell() throws InterruptedException {
        int threads      = 10;
        BigDecimal each  = new BigDecimal("1500");  // 10 × 1500 = 15000 > 10000

        ExecutorService pool   = Executors.newFixedThreadPool(threads);
        CountDownLatch  start  = new CountDownLatch(1);
        CountDownLatch  done   = new CountDownLatch(threads);

        AtomicInteger allowCount = new AtomicInteger(0);
        AtomicInteger denyCount  = new AtomicInteger(0);
        List<String>  codes      = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    QuotaDecision d = quotaService.authorize(
                            buildRequest(each, "idem-concurrent-" + idx));
                    if (d.isAllowed()) {
                        allowCount.incrementAndGet();
                        codes.add(d.getReservationCode());
                    } else {
                        denyCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Optimistic lock exhausted counts as implicit deny
                    denyCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        // At most 6 × 1500 = 9000 ≤ 10000 can be ALLOW
        // (6 allows = 9000, 7th would need 10500 > 10000 — so ≤ 6)
        assertThat(allowCount.get()).isLessThanOrEqualTo(6);
        assertThat(allowCount.get() + denyCount.get()).isEqualTo(threads);

        // Bucket invariant — no oversell, no negatives
        QuotaBucket bucket = freshBucket();
        assertThat(bucket.getReservedAmount())
                .isLessThanOrEqualTo(bucket.getTotalAmount());
        assertThat(bucket.getUsedAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(bucket.getReservedAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(bucket.availableAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Verify reserved == sum of approved reservations
        BigDecimal expectedReserved = each.multiply(BigDecimal.valueOf(allowCount.get()));
        assertThat(bucket.getReservedAmount()).isEqualByComparingTo(expectedReserved);

        // Cleanup reservations (afterEach handles bucket/pool)
        for (String code : codes) {
            try { quotaService.release(code); } catch (Exception ignored) {}
        }
    }
}
