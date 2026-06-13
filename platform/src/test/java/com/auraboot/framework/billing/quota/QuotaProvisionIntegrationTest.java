package com.auraboot.framework.billing.quota;

import com.auraboot.framework.billing.BillingAccountSeedHelper;
import com.auraboot.framework.billing.quota.mapper.*;
import com.auraboot.framework.billing.quota.model.*;
import com.auraboot.framework.billing.quota.spi.*;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link QuotaService#provision} and {@link QuotaService#provisionAll}.
 *
 * <p>Runs against the real {@code aura_boot} test database.
 * The quota migration (2026-06-10-billing-quota.sql) must be applied before running.
 *
 * <p>Tests do NOT use class-level @Transactional so that committed rows survive
 * across the provision→authorize→commit end-to-end flow.
 * Cleanup is done in @AfterEach via direct mapper deletes.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuotaProvisionIntegrationTest extends BaseIntegrationTest {

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

    private static final Long TEST_ACCOUNT_ID   = 900_100_001L;
    private static final Long TEST_SUBSCRIPTION = 900_100_002L;
    private static final String AI_TOKEN        = "AI_TOKEN";
    private static final String API_CALL        = "API_CALL";

    private static final Instant PERIOD_START =
            Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant PERIOD_END =
            PERIOD_START.plus(30, ChronoUnit.DAYS);

    @BeforeEach
    void seedParentAccount() {
        // Seed parent ab_billing_account row so FK on quota_pool/bucket is satisfied.
        // Task 2 FK-ized account_id → ab_billing_account(id); bare IDs now require a parent row.
        billingAccountSeedHelper.ensureAccountExists(TEST_ACCOUNT_ID, "ACC-IT-" + TEST_ACCOUNT_ID);
    }

    @AfterEach
    void cleanup() {
        // Delete in FK order: ledger → lines → reservations → buckets → pools
        ledgerMapper.delete(new LambdaQueryWrapper<QuotaLedger>()
                .eq(QuotaLedger::getAccountId, TEST_ACCOUNT_ID));
        reservationLineMapper.delete(new LambdaQueryWrapper<QuotaReservationLine>()
                .inSql(QuotaReservationLine::getBucketId,
                        "SELECT id FROM ab_billing_quota_bucket WHERE account_id = " + TEST_ACCOUNT_ID));
        reservationMapper.delete(new LambdaQueryWrapper<QuotaReservation>()
                .eq(QuotaReservation::getAccountId, TEST_ACCOUNT_ID));
        quotaBucketMapper.delete(new LambdaQueryWrapper<QuotaBucket>()
                .eq(QuotaBucket::getAccountId, TEST_ACCOUNT_ID));
        quotaPoolMapper.delete(new LambdaQueryWrapper<QuotaPool>()
                .eq(QuotaPool::getAccountId, TEST_ACCOUNT_ID));
        // Remove the seeded billing account (parent of pool/bucket FK).
        billingAccountSeedHelper.removeAccount(TEST_ACCOUNT_ID);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private QuotaGrantRequest buildGrantRequest(String resourceCode, BigDecimal amount, String idempotencyKey) {
        return QuotaGrantRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .subscriptionId(TEST_SUBSCRIPTION)
                .resourceCode(resourceCode)
                .amount(amount)
                .unit("TOKEN")
                .periodStart(PERIOD_START)
                .periodEnd(PERIOD_END)
                .sourceType(BucketSourceType.BASE_PLAN)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private List<QuotaLedger> grantLedgersForAccount() {
        return ledgerMapper.selectList(new LambdaQueryWrapper<QuotaLedger>()
                .eq(QuotaLedger::getAccountId, TEST_ACCOUNT_ID)
                .eq(QuotaLedger::getOperationType, OperationType.GRANT.name()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 1: provision AI_TOKEN 1_000_000 → bucket built + GRANT ledger
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("provision AI_TOKEN 1_000_000 → bucket total=1000000 used=0 reserved=0 + 1 GRANT ledger balanceAfter=1000000")
    void provisionCreatesActiveBucketWithGrantLedger() {
        BigDecimal amount = new BigDecimal("1000000");
        String idempotencyKey = "prov-case1-" + UUID.randomUUID();

        QuotaBucket bucket = quotaService.provision(buildGrantRequest(AI_TOKEN, amount, idempotencyKey));

        // Bucket assertions
        assertThat(bucket).isNotNull();
        assertThat(bucket.getId()).isNotNull();
        assertThat(bucket.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
        assertThat(bucket.getResourceCode()).isEqualTo(AI_TOKEN);
        assertThat(bucket.getTotalAmount()).isEqualByComparingTo(amount);
        assertThat(bucket.getUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(bucket.getReservedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(bucket.getStatus()).isEqualTo(BucketStatus.ACTIVE.name());
        assertThat(bucket.getVersion()).isEqualTo(0L);
        assertThat(bucket.getPriority()).isEqualTo(100);
        assertThat(bucket.getOveragePolicy()).isEqualTo(OveragePolicy.HARD_LIMIT.name());

        // Available balance = total
        assertThat(bucket.availableAmount()).isEqualByComparingTo(amount);

        // Pool was auto-created
        QuotaPool pool = quotaPoolMapper.selectById(bucket.getPoolId());
        assertThat(pool).isNotNull();
        assertThat(pool.getAccountId()).isEqualTo(TEST_ACCOUNT_ID);
        assertThat(pool.getResourceCode()).isEqualTo(AI_TOKEN);
        assertThat(pool.getPoolType()).isEqualTo(PoolType.DEDICATED.name());
        assertThat(pool.getScopeType()).isEqualTo(ScopeType.ACCOUNT.name());

        // Exactly 1 GRANT ledger entry
        List<QuotaLedger> grants = grantLedgersForAccount();
        assertThat(grants).hasSize(1);
        QuotaLedger grant = grants.get(0);
        assertThat(grant.getBucketId()).isEqualTo(bucket.getId());
        assertThat(grant.getOperationType()).isEqualTo(OperationType.GRANT.name());
        assertThat(grant.getAmount()).isEqualByComparingTo(amount);
        assertThat(grant.getBalanceAfter()).isEqualByComparingTo(amount);
        assertThat(grant.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(grant.getReservationId()).isNull();   // GRANT has no reservation
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 2: idempotency — same idempotencyKey → return original bucket, no dup
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("provision same idempotencyKey twice → returns original bucket, no duplicate bucket/ledger")
    void idempotentProvisionReturnsSameBucket() {
        String idempotencyKey = "prov-idem-" + UUID.randomUUID();
        QuotaGrantRequest req = buildGrantRequest(AI_TOKEN, new BigDecimal("500000"), idempotencyKey);

        QuotaBucket first  = quotaService.provision(req);
        QuotaBucket second = quotaService.provision(req);

        // Same bucket ID returned
        assertThat(second.getId()).isEqualTo(first.getId());

        // Only 1 bucket in DB for this account+resource
        List<QuotaBucket> buckets = quotaBucketMapper.selectList(
                new LambdaQueryWrapper<QuotaBucket>()
                        .eq(QuotaBucket::getAccountId, TEST_ACCOUNT_ID)
                        .eq(QuotaBucket::getResourceCode, AI_TOKEN));
        assertThat(buckets).hasSize(1);

        // Only 1 GRANT ledger entry (not 2)
        List<QuotaLedger> grants = grantLedgersForAccount();
        assertThat(grants).hasSize(1);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 3: invalid resource code → throws IllegalArgumentException
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("provision with unregistered resource_code → throws IllegalArgumentException")
    void provisionUnregisteredResourceThrows() {
        QuotaGrantRequest req = QuotaGrantRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .subscriptionId(TEST_SUBSCRIPTION)
                .resourceCode("NONEXISTENT_XYZ_99999")
                .amount(new BigDecimal("100"))
                .unit("TOKEN")
                .periodStart(PERIOD_START)
                .periodEnd(PERIOD_END)
                .sourceType(BucketSourceType.BASE_PLAN)
                .idempotencyKey("prov-bad-res-" + UUID.randomUUID())
                .build();

        assertThatThrownBy(() -> quotaService.provision(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONEXISTENT_XYZ_99999");

        // No bucket or ledger should be created
        assertThat(quotaBucketMapper.selectList(
                new LambdaQueryWrapper<QuotaBucket>()
                        .eq(QuotaBucket::getAccountId, TEST_ACCOUNT_ID))).isEmpty();
        assertThat(grantLedgersForAccount()).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 4: end-to-end — provision then authorize→commit succeeds
    //         (provisioned bucket can be consumed)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("provision AI_TOKEN 200000, then authorize 50000 → commit 40000 → bucket used=40000")
    void provisionedBucketIsConsumable() {
        BigDecimal provisionAmount = new BigDecimal("200000");
        BigDecimal reserveAmount   = new BigDecimal("50000");
        BigDecimal actualAmount    = new BigDecimal("40000");

        // 1. Provision
        QuotaBucket bucket = quotaService.provision(
                buildGrantRequest(AI_TOKEN, provisionAmount, "prov-e2e-" + UUID.randomUUID()));
        assertThat(bucket.getTotalAmount()).isEqualByComparingTo(provisionAmount);

        // 2. Authorize (reserve) against the provisioned bucket
        QuotaDecision decision = quotaService.authorize(
                QuotaAuthorizeRequest.builder()
                        .accountId(TEST_ACCOUNT_ID)
                        .subscriptionId(TEST_SUBSCRIPTION)
                        .resourceCode(AI_TOKEN)
                        .estimatedQuantity(reserveAmount)
                        .idempotencyKey("auth-e2e-" + UUID.randomUUID())
                        .build());

        assertThat(decision.isAllowed())
                .as("authorize should succeed against provisioned bucket")
                .isTrue();
        assertThat(decision.getReservationCode()).isNotBlank();

        // 3. Verify reserved amount
        QuotaBucket afterReserve = quotaBucketMapper.selectById(bucket.getId());
        assertThat(afterReserve.getReservedAmount()).isEqualByComparingTo(reserveAmount);

        // 4. Commit with actual quantity
        QuotaCommitResult result = quotaService.commit(
                decision.getReservationCode(), actualAmount);
        assertThat(result.getActualAmount()).isEqualByComparingTo(actualAmount);
        assertThat(result.getReleasedDelta())
                .isEqualByComparingTo(reserveAmount.subtract(actualAmount));  // 10000 released

        // 5. Final bucket state: used=40000, reserved=0
        QuotaBucket afterCommit = quotaBucketMapper.selectById(bucket.getId());
        assertThat(afterCommit.getUsedAmount()).isEqualByComparingTo(actualAmount);
        assertThat(afterCommit.getReservedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        // available = 200000 - 40000 - 0 = 160000
        assertThat(afterCommit.availableAmount()).isEqualByComparingTo(new BigDecimal("160000"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Case 5: provisionAll — 2 resources → 2 buckets + 2 GRANT ledgers
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("provisionAll [AI_TOKEN 1000000, API_CALL 50000] → 2 buckets + 2 GRANT ledgers")
    void provisionAllCreatesTwoBuckets() {
        String idempotencyKey1 = "prov-all-ai-"  + UUID.randomUUID();
        String idempotencyKey2 = "prov-all-api-" + UUID.randomUUID();

        List<QuotaGrantRequest> reqs = List.of(
                buildGrantRequest(AI_TOKEN,  new BigDecimal("1000000"), idempotencyKey1),
                QuotaGrantRequest.builder()
                        .accountId(TEST_ACCOUNT_ID)
                        .subscriptionId(TEST_SUBSCRIPTION)
                        .resourceCode(API_CALL)
                        .amount(new BigDecimal("50000"))
                        .unit("CALL")
                        .periodStart(PERIOD_START)
                        .periodEnd(PERIOD_END)
                        .sourceType(BucketSourceType.BASE_PLAN)
                        .idempotencyKey(idempotencyKey2)
                        .build()
        );

        List<QuotaBucket> buckets = quotaService.provisionAll(reqs);

        assertThat(buckets).hasSize(2);

        QuotaBucket aiBucket  = buckets.get(0);
        QuotaBucket apiBucket = buckets.get(1);

        assertThat(aiBucket.getResourceCode()).isEqualTo(AI_TOKEN);
        assertThat(aiBucket.getTotalAmount()).isEqualByComparingTo("1000000");

        assertThat(apiBucket.getResourceCode()).isEqualTo(API_CALL);
        assertThat(apiBucket.getTotalAmount()).isEqualByComparingTo("50000");
        assertThat(apiBucket.getUnit()).isEqualTo("CALL");

        // 2 GRANT ledger entries (one per bucket)
        List<QuotaLedger> grants = grantLedgersForAccount();
        assertThat(grants).hasSize(2);

        // Each ledger correctly records amount and balanceAfter
        assertThat(grants.stream()
                .filter(l -> l.getBucketId().equals(aiBucket.getId()))
                .findFirst()).isPresent().get()
                .satisfies(l -> {
                    assertThat(l.getAmount()).isEqualByComparingTo("1000000");
                    assertThat(l.getBalanceAfter()).isEqualByComparingTo("1000000");
                });

        assertThat(grants.stream()
                .filter(l -> l.getBucketId().equals(apiBucket.getId()))
                .findFirst()).isPresent().get()
                .satisfies(l -> {
                    assertThat(l.getAmount()).isEqualByComparingTo("50000");
                    assertThat(l.getBalanceAfter()).isEqualByComparingTo("50000");
                });
    }
}
