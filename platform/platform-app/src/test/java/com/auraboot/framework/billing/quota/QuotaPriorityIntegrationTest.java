package com.auraboot.framework.billing.quota;

import com.auraboot.framework.billing.BillingAccountSeedHelper;
import com.auraboot.framework.billing.quota.mapper.*;
import com.auraboot.framework.billing.quota.model.*;
import com.auraboot.framework.billing.quota.spi.*;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the multi-bucket consumption priority algorithm (M2 slice).
 *
 * <p>Verifies the five P0-10 priority rules:
 * <ol>
 *   <li>Expiry preemption beats source-type order.
 *   <li>Source-type business order (PROMOTION {@literal <} BASE_PLAN {@literal <} PREPAID_CREDIT).
 *   <li>Multi-bucket spanning: single authorize drains 2 buckets; 2 reservation_lines produced.
 *   <li>Commit / release correctly applies to each line's bucket.
 *   <li>Regression: idempotency still holds in multi-bucket setup.
 * </ol>
 *
 * <p>Does NOT extend {@code @Transactional} at class level — CAS updates need real commits.
 * Cleanup is performed in {@code @AfterEach}.
 *
 * <p><b>Isolation strategy</b>: each test method generates a unique account ID
 * (base {@value #ACCOUNT_BASE} + per-test counter) so {@code listActiveBucketsForReserve}
 * cannot see buckets created by other test methods.  {@code @AfterEach} deletes all rows
 * for the per-test account ID.
 *
 * <p>Prerequisites: billing migration applied to the {@code aura_boot} test DB.
 * {@code psql -h localhost -d aura_boot -f platform/src/main/resources/database/migrations/2026-06-10-billing-quota.sql}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuotaPriorityIntegrationTest extends BaseIntegrationTest {

    @Autowired private BillingAccountSeedHelper   billingAccountSeedHelper;
    @Autowired private QuotaService              quotaService;
    @Autowired private QuotaPoolMapper            quotaPoolMapper;
    @Autowired private QuotaBucketMapper          quotaBucketMapper;
    @Autowired private QuotaReservationMapper     reservationMapper;
    @Autowired private QuotaReservationLineMapper reservationLineMapper;
    @Autowired private QuotaLedgerMapper          ledgerMapper;

    // ── Per-test isolation ───────────────────────────────────────────────────

    /** High base to avoid clashing with QuotaServiceIntegrationTest (900_000_001). */
    private static final long ACCOUNT_BASE = 900_001_000L;
    private static final AtomicLong COUNTER = new AtomicLong(0);

    private static final String RESOURCE = "AI_TOKEN";

    /** Per-test unique account ID — guarantees bucket isolation across methods. */
    private Long accountId;
    /** Per-test subscription ID. */
    private Long subscriptionId;
    /** Pool for this test. */
    private Long poolId;
    /** Buckets created in this test (used for cleanup). */
    private final List<Long> createdBucketIds = new ArrayList<>();

    // ── Setup / Teardown ─────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        createdBucketIds.clear();
        long offset    = COUNTER.incrementAndGet();
        accountId      = ACCOUNT_BASE + offset;
        subscriptionId = ACCOUNT_BASE + offset + 10_000L;

        // Seed parent ab_billing_account row so FK on quota_pool/bucket is satisfied.
        // Task 2 FK-ized account_id → ab_billing_account(id); each test gets a unique accountId.
        billingAccountSeedHelper.ensureAccountExists(accountId, "ACC-PRIO-" + accountId);

        QuotaPool pool = QuotaPool.builder()
                .poolCode("prio-pool-" + UUID.randomUUID())
                .accountId(accountId)
                .subscriptionId(subscriptionId)
                .resourceCode(RESOURCE)
                .scopeType(ScopeType.ACCOUNT.name())
                .poolType(PoolType.DEDICATED.name())
                .status(BucketStatus.ACTIVE.name())
                .build();
        quotaPoolMapper.insert(pool);
        poolId = pool.getId();
    }

    @AfterEach
    void tearDown() {
        // Cascade delete: ledger → reservation_lines → reservations → buckets → pool
        ledgerMapper.delete(lqw(QuotaLedger.class)
                .eq(QuotaLedger::getAccountId, accountId));
        for (Long bid : createdBucketIds) {
            reservationLineMapper.delete(lqw(QuotaReservationLine.class)
                    .eq(QuotaReservationLine::getBucketId, bid));
        }
        reservationMapper.delete(lqw(QuotaReservation.class)
                .eq(QuotaReservation::getAccountId, accountId));
        for (Long bid : createdBucketIds) {
            quotaBucketMapper.deleteById(bid);
        }
        quotaPoolMapper.deleteById(poolId);
        // Remove the seeded billing account (parent of pool/bucket FK).
        billingAccountSeedHelper.removeAccount(accountId);
    }

    /** Short-hand for creating a LambdaQueryWrapper. */
    @SuppressWarnings("unchecked")
    private static <T> com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<T>
    lqw(Class<T> ignored) {
        return new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private QuotaBucket makeBucket(BucketSourceType sourceType, int priority,
                                    BigDecimal total, Instant periodEnd) {
        QuotaBucket b = QuotaBucket.builder()
                .bucketCode("b-" + UUID.randomUUID())
                .poolId(poolId)
                .accountId(accountId)
                .subscriptionId(subscriptionId)
                .resourceCode(RESOURCE)
                .totalAmount(total)
                .usedAmount(BigDecimal.ZERO)
                .reservedAmount(BigDecimal.ZERO)
                .unit("TOKEN")
                .periodStart(Instant.now().minus(1, ChronoUnit.HOURS))
                .periodEnd(periodEnd)
                .sourceType(sourceType.name())
                .priority(priority)
                .overagePolicy(OveragePolicy.HARD_LIMIT.name())
                .status(BucketStatus.ACTIVE.name())
                .version(0L)
                .build();
        quotaBucketMapper.insert(b);
        createdBucketIds.add(b.getId());
        return b;
    }

    private QuotaAuthorizeRequest req(BigDecimal qty, String idempotencyKey) {
        return QuotaAuthorizeRequest.builder()
                .accountId(accountId)
                .subscriptionId(subscriptionId)
                .resourceCode(RESOURCE)
                .estimatedQuantity(qty)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private QuotaBucket fresh(Long id) {
        return quotaBucketMapper.selectById(id);
    }

    private List<QuotaReservationLine> linesFor(String reservationCode) {
        QuotaReservation res = reservationMapper.selectOne(
                lqw(QuotaReservation.class)
                        .eq(QuotaReservation::getReservationCode, reservationCode));
        assertThat(res).isNotNull();
        return reservationLineMapper.selectList(
                lqw(QuotaReservationLine.class)
                        .eq(QuotaReservationLine::getReservationId, res.getId())
                        .orderByAsc(QuotaReservationLine::getId));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 1: Expiry preemption beats source-type order
    //
    // ADD_ON expires in 5 days (expiringSoon=true, threshold default 7)
    // PROMOTION expires in 30 days (expiringSoon=false)
    //
    // Expected: ADD_ON bucket drained first (expiry preemption wins over
    //           PROMOTION's lower consumptionOrder of 1).
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("expiry preemption: ADD_ON expiring-soon drains before PROMOTION with 30-day period")
    void expiryPreemptionBeatsSourceTypeOrder() {
        // ADD_ON: expires in 5 days → expiringSoon=true (threshold=7 default)
        QuotaBucket addOnBucket = makeBucket(
                BucketSourceType.ADD_ON, 100,
                new BigDecimal("5000"),
                Instant.now().plus(5, ChronoUnit.DAYS));

        // PROMOTION: expires in 30 days → expiringSoon=false
        QuotaBucket promoBucket = makeBucket(
                BucketSourceType.PROMOTION, 100,
                new BigDecimal("5000"),
                Instant.now().plus(30, ChronoUnit.DAYS));

        // Authorize 3000 — should come entirely from the expiring ADD_ON bucket
        QuotaDecision decision = quotaService.authorize(
                req(new BigDecimal("3000"), "prio-case1-" + UUID.randomUUID()));
        assertThat(decision.isAllowed()).isTrue();

        List<QuotaReservationLine> lines = linesFor(decision.getReservationCode());
        assertThat(lines).as("only one bucket needed").hasSize(1);
        assertThat(lines.get(0).getBucketId())
                .as("expiring ADD_ON bucket should be drained first, not PROMOTION")
                .isEqualTo(addOnBucket.getId());

        assertThat(fresh(addOnBucket.getId()).getReservedAmount()).isEqualByComparingTo("3000");
        assertThat(fresh(promoBucket.getId()).getReservedAmount()).isEqualByComparingTo("0");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 2: Source-type business order (none expiring-soon)
    //
    // All expire in 30 days; same explicit priority (100).
    // PROMOTION(order=1) < BASE_PLAN(order=4) < PREPAID_CREDIT(order=6)
    //
    // Authorize 3000; PROMOTION has only 2000 → spans into BASE_PLAN.
    // PREPAID_CREDIT should be untouched.
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("source-type order: PROMOTION drained before BASE_PLAN before PREPAID_CREDIT")
    void sourceTypeBusinessOrder() {
        Instant far = Instant.now().plus(30, ChronoUnit.DAYS);

        QuotaBucket promoBucket    = makeBucket(BucketSourceType.PROMOTION,      100, new BigDecimal("2000"), far);
        QuotaBucket baseBucket     = makeBucket(BucketSourceType.BASE_PLAN,      100, new BigDecimal("5000"), far);
        QuotaBucket prepaidBucket  = makeBucket(BucketSourceType.PREPAID_CREDIT, 100, new BigDecimal("5000"), far);

        // Authorize 3000 — expect 2000 from PROMOTION + 1000 from BASE_PLAN
        QuotaDecision decision = quotaService.authorize(
                req(new BigDecimal("3000"), "prio-case2-" + UUID.randomUUID()));
        assertThat(decision.isAllowed()).isTrue();

        List<QuotaReservationLine> lines = linesFor(decision.getReservationCode());
        assertThat(lines).hasSize(2);

        assertThat(lines.get(0).getBucketId())
                .as("first line from PROMOTION")
                .isEqualTo(promoBucket.getId());
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("2000");

        assertThat(lines.get(1).getBucketId())
                .as("second line from BASE_PLAN")
                .isEqualTo(baseBucket.getId());
        assertThat(lines.get(1).getAmount()).isEqualByComparingTo("1000");

        assertThat(fresh(promoBucket.getId()).getReservedAmount()).isEqualByComparingTo("2000");
        assertThat(fresh(baseBucket.getId()).getReservedAmount()).isEqualByComparingTo("1000");
        assertThat(fresh(prepaidBucket.getId()).getReservedAmount()).isEqualByComparingTo("0");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 3: Multi-bucket spanning — greedy allocation
    //
    // Two buckets: PROMOTION priority=10 total=3000, BASE_PLAN priority=20 total=3000.
    // Authorize 5000 → spans both (3000 + 2000), 2 lines produced.
    // A second authorize of 2000 → DENY (only 1000 left).
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("multi-bucket spanning: authorize > single bucket → 2 reservation_lines, DENY when exhausted")
    void multiBucketSpanning() {
        Instant far = Instant.now().plus(30, ChronoUnit.DAYS);

        QuotaBucket first  = makeBucket(BucketSourceType.PROMOTION, 10, new BigDecimal("3000"), far);
        QuotaBucket second = makeBucket(BucketSourceType.BASE_PLAN, 20, new BigDecimal("3000"), far);

        QuotaDecision decision = quotaService.authorize(
                req(new BigDecimal("5000"), "prio-case3-" + UUID.randomUUID()));
        assertThat(decision.isAllowed()).isTrue();

        List<QuotaReservationLine> lines = linesFor(decision.getReservationCode());
        assertThat(lines).as("exactly 2 lines for 2-bucket span").hasSize(2);

        assertThat(lines.get(0).getBucketId()).isEqualTo(first.getId());
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("3000");

        assertThat(lines.get(1).getBucketId()).isEqualTo(second.getId());
        assertThat(lines.get(1).getAmount()).isEqualByComparingTo("2000");

        assertThat(fresh(first.getId()).getReservedAmount()).isEqualByComparingTo("3000");
        assertThat(fresh(second.getId()).getReservedAmount()).isEqualByComparingTo("2000");

        // Only 1000 remains → 2000 request must DENY
        QuotaDecision deny = quotaService.authorize(
                req(new BigDecimal("2000"), "prio-case3-deny-" + UUID.randomUUID()));
        assertThat(deny.isAllowed()).isFalse();
        assertThat(deny.getDenyReason()).isEqualTo("INSUFFICIENT_QUOTA");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 4a: Commit across multiple bucket lines
    //
    // Reserve 5000 across 2 buckets (3000 + 2000).
    // Commit actual=4500 → proportional: 2700 from first, 1800 from second.
    // After commit: used correct, reserved=0, no negatives.
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("commit multi-bucket: proportional used/reserved per line")
    void commitMultiBucket() {
        Instant far = Instant.now().plus(30, ChronoUnit.DAYS);

        QuotaBucket first  = makeBucket(BucketSourceType.PROMOTION, 10, new BigDecimal("3000"), far);
        QuotaBucket second = makeBucket(BucketSourceType.BASE_PLAN, 20, new BigDecimal("3000"), far);

        QuotaDecision decision = quotaService.authorize(
                req(new BigDecimal("5000"), "prio-case4a-" + UUID.randomUUID()));
        assertThat(decision.isAllowed()).isTrue();
        assertThat(linesFor(decision.getReservationCode())).hasSize(2);

        // Commit actual = 4500
        // Proportional: 4500 × (3000/5000) = 2700 from first bucket
        //               4500 × (2000/5000) = 1800 from second bucket
        QuotaCommitResult result = quotaService.commit(decision.getReservationCode(), new BigDecimal("4500"));
        assertThat(result.getActualAmount()).isEqualByComparingTo("4500");
        assertThat(result.getReleasedDelta()).isEqualByComparingTo("500");

        QuotaBucket afterFirst  = fresh(first.getId());
        QuotaBucket afterSecond = fresh(second.getId());

        assertThat(afterFirst.getUsedAmount()).isEqualByComparingTo("2700");
        assertThat(afterFirst.getReservedAmount()).isEqualByComparingTo("0");

        assertThat(afterSecond.getUsedAmount()).isEqualByComparingTo("1800");
        assertThat(afterSecond.getReservedAmount()).isEqualByComparingTo("0");

        // Non-negative invariants
        assertThat(afterFirst.availableAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(afterSecond.availableAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 4b: Release across multiple bucket lines
    //
    // Reserve 3000 across 2 buckets (2000 + 1000).
    // Release → each bucket reserved restored to 0, available back to full.
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("release multi-bucket: each bucket reserved restored to 0")
    void releaseMultiBucket() {
        Instant far = Instant.now().plus(30, ChronoUnit.DAYS);

        QuotaBucket first  = makeBucket(BucketSourceType.PROMOTION, 10, new BigDecimal("2000"), far);
        QuotaBucket second = makeBucket(BucketSourceType.BASE_PLAN, 20, new BigDecimal("2000"), far);

        QuotaDecision decision = quotaService.authorize(
                req(new BigDecimal("3000"), "prio-case4b-" + UUID.randomUUID()));
        assertThat(decision.isAllowed()).isTrue();
        assertThat(linesFor(decision.getReservationCode())).hasSize(2);

        quotaService.release(decision.getReservationCode());

        assertThat(fresh(first.getId()).getReservedAmount()).isEqualByComparingTo("0");
        assertThat(fresh(second.getId()).getReservedAmount()).isEqualByComparingTo("0");
        assertThat(fresh(first.getId()).availableAmount()).isEqualByComparingTo("2000");
        assertThat(fresh(second.getId()).availableAmount()).isEqualByComparingTo("2000");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 5: Regression — idempotency holds in multi-bucket setup
    //
    // Same idempotency key sent twice → second call returns same reservation code.
    // Buckets deducted only once (no double-deduct).
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("regression idempotency: same key with multi-bucket → same reservation, no double deduct")
    void idempotencyRegressionMultiBucket() {
        Instant far = Instant.now().plus(30, ChronoUnit.DAYS);

        QuotaBucket b1 = makeBucket(BucketSourceType.PROMOTION, 10, new BigDecimal("1000"), far);
        QuotaBucket b2 = makeBucket(BucketSourceType.BASE_PLAN, 20, new BigDecimal("2000"), far);

        String key = "prio-idem-" + UUID.randomUUID();
        QuotaDecision first  = quotaService.authorize(req(new BigDecimal("2500"), key));
        QuotaDecision second = quotaService.authorize(req(new BigDecimal("2500"), key));

        assertThat(first.isAllowed()).isTrue();
        assertThat(second.isAllowed()).isTrue();
        assertThat(second.getReservationCode())
                .as("second call must return same reservation code")
                .isEqualTo(first.getReservationCode());

        // No double deduct: b1=1000 reserved, b2=1500 reserved (not 2000+3000)
        assertThat(fresh(b1.getId()).getReservedAmount()).isEqualByComparingTo("1000");
        assertThat(fresh(b2.getId()).getReservedAmount()).isEqualByComparingTo("1500");
    }
}
