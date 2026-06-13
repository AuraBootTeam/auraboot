package com.auraboot.framework.billing.quota.service;

import com.auraboot.framework.billing.catalog.spi.ResourceCatalogService;
import com.auraboot.framework.billing.quota.config.BillingQuotaProperties;
import com.auraboot.framework.billing.quota.mapper.*;
import com.auraboot.framework.billing.quota.model.*;
import com.auraboot.framework.billing.quota.spi.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OSS base implementation of {@link QuotaService}.
 *
 * <h3>Supported features (OSS)</h3>
 * <ul>
 *   <li>Multi-bucket RESERVE with true consumption priority (P0-10 rules):
 *       expiry-preemption → explicit bucket.priority → source-type business order
 *       → period_end → id.
 *   <li>Hard-limit enforcement only — DENY when total available {@literal <} estimated.
 *   <li>Greedy multi-bucket allocation: each bucket gets a
 *       {@link QuotaReservationLine}; commit/release processed line-by-line.
 *   <li>Commit with actual {@literal ≤} estimated; proportional rollback of difference.
 *   <li>Idempotent authorize via DB UNIQUE(account_id, idempotency_key).
 *   <li>Optimistic locking with retry (up to {@value #MAX_RETRY} attempts per bucket).
 *   <li>Expiry-preempt threshold configurable via
 *       {@code auraboot.billing.quota.expiry-preempt-days} (default 7).
 * </ul>
 *
 * <h3>TODO / Enterprise gaps</h3>
 * <ul>
 *   <li><b>G2-01</b>: OveragePolicy != HARD_LIMIT enforcement (SOFT_LIMIT / OVERAGE_CHARGE).
 *   <li><b>G2-02</b>: Commit with actual {@literal >} estimated (top-up deduction).
 *   <li><b>G5</b>: Shared-pool routing (THROTTLE / DOWNGRADE strategies).
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaServiceImpl implements QuotaService {

    /** Maximum optimistic-lock retry attempts before giving up. */
    public static final int MAX_RETRY = 5;

    private static final Duration DEFAULT_RESERVATION_TTL = Duration.ofMinutes(30);
    private static final String STATUS_ACTIVE = BucketStatus.ACTIVE.name();

    private final ResourceCatalogService  resourceCatalogService;
    private final QuotaPoolMapper         quotaPoolMapper;
    private final QuotaBucketMapper       quotaBucketMapper;
    private final QuotaReservationMapper     reservationMapper;
    private final QuotaReservationLineMapper reservationLineMapper;
    private final QuotaLedgerMapper       ledgerMapper;
    private final BillingQuotaProperties  billingQuotaProperties;

    // ─────────────────────────────────────────────────────────────────────────
    // provision (GRANT)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public QuotaBucket provision(QuotaGrantRequest req) {

        // ── input validation ──────────────────────────────────────────────────
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "[quota-provision] amount must be positive, got: " + req.getAmount());
        }
        if (!resourceCatalogService.isRegistered(req.getResourceCode())) {
            throw new IllegalArgumentException(
                    "[quota-provision] resource not registered: " + req.getResourceCode());
        }

        // ── idempotency: query GRANT ledger by (accountId, idempotencyKey) ────
        // Using ledger as the idempotency store avoids an extra unique index on
        // quota_bucket (bucket rows are recycled across periods; the ledger is
        // append-only and never recycled).
        QuotaLedger existingLedger = ledgerMapper.selectOne(
                new LambdaQueryWrapper<QuotaLedger>()
                        .eq(QuotaLedger::getAccountId, req.getAccountId())
                        .eq(QuotaLedger::getIdempotencyKey, req.getIdempotencyKey())
                        .eq(QuotaLedger::getOperationType, OperationType.GRANT.name())
                        .last("LIMIT 1")
        );
        if (existingLedger != null) {
            log.debug("[quota-provision] idempotent hit — returning existing bucket id={}",
                    existingLedger.getBucketId());
            return requireBucket(existingLedger.getBucketId());
        }

        // ── find or create quota_pool ─────────────────────────────────────────
        // Pool key: (accountId, resourceCode, scopeType=ACCOUNT, poolType=DEDICATED).
        // Workspace/user-scoped pools are out of scope for OSS (TODO gap G5).
        QuotaPool pool = quotaPoolMapper.selectOne(
                new LambdaQueryWrapper<QuotaPool>()
                        .eq(QuotaPool::getAccountId, req.getAccountId())
                        .eq(QuotaPool::getResourceCode, req.getResourceCode())
                        .eq(QuotaPool::getScopeType, ScopeType.ACCOUNT.name())
                        .eq(QuotaPool::getPoolType, PoolType.DEDICATED.name())
                        .last("LIMIT 1")
        );
        if (pool == null) {
            pool = QuotaPool.builder()
                    .poolCode("pool-" + UUID.randomUUID().toString().replace("-", ""))
                    .accountId(req.getAccountId())
                    .workspaceId(req.getWorkspaceId())
                    .subscriptionId(req.getSubscriptionId() != null ? req.getSubscriptionId() : 0L)
                    .resourceCode(req.getResourceCode())
                    .scopeType(ScopeType.ACCOUNT.name())
                    .poolType(PoolType.DEDICATED.name())
                    .status(BucketStatus.ACTIVE.name())
                    .build();
            quotaPoolMapper.insert(pool);
            log.debug("[quota-provision] created pool id={} for account={} resource={}",
                    pool.getId(), req.getAccountId(), req.getResourceCode());
        }

        // ── create quota_bucket ───────────────────────────────────────────────
        int priority      = req.getPriority()      != null ? req.getPriority()      : 100;
        OveragePolicy ovp = req.getOveragePolicy()  != null ? req.getOveragePolicy() : OveragePolicy.HARD_LIMIT;

        QuotaBucket bucket = QuotaBucket.builder()
                .bucketCode("bucket-" + UUID.randomUUID().toString().replace("-", ""))
                .poolId(pool.getId())
                .accountId(req.getAccountId())
                .userId(req.getUserId())
                .subscriptionId(req.getSubscriptionId() != null ? req.getSubscriptionId() : 0L)
                .resourceCode(req.getResourceCode())
                .totalAmount(req.getAmount())
                .usedAmount(BigDecimal.ZERO)
                .reservedAmount(BigDecimal.ZERO)
                .unit(req.getUnit())
                .periodStart(req.getPeriodStart())
                .periodEnd(req.getPeriodEnd())
                .sourceType(req.getSourceType().name())
                .priority(priority)
                .overagePolicy(ovp.name())
                .status(BucketStatus.ACTIVE.name())
                .version(0L)
                .build();
        quotaBucketMapper.insert(bucket);
        log.info("[quota-provision] created bucket id={} account={} resource={} amount={} unit={}",
                bucket.getId(), req.getAccountId(), req.getResourceCode(),
                req.getAmount(), req.getUnit());

        // ── write GRANT ledger entry ──────────────────────────────────────────
        // balance_after = amount (bucket is brand new: total - used - reserved = amount)
        QuotaLedger grantEntry = QuotaLedger.builder()
                .ledgerCode("LED-" + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase())
                .bucketId(bucket.getId())
                .reservationId(null)        // GRANT is not associated with a reservation
                .accountId(req.getAccountId())
                .subscriptionId(req.getSubscriptionId() != null ? req.getSubscriptionId() : 0L)
                .operationType(OperationType.GRANT.name())
                .amount(req.getAmount())
                .balanceAfter(req.getAmount())
                .idempotencyKey(req.getIdempotencyKey())
                .occurredAt(Instant.now())
                .build();
        ledgerMapper.insert(grantEntry);
        log.info("[quota-provision] wrote GRANT ledger id={} bucket={} amount={}",
                grantEntry.getId(), bucket.getId(), req.getAmount());

        return bucket;
    }

    /**
     * Override the SPI default to wrap the entire batch in a single transaction,
     * so that a failure on any element rolls back all previously provisioned buckets.
     */
    @Override
    @Transactional
    public List<QuotaBucket> provisionAll(List<QuotaGrantRequest> reqs) {
        List<QuotaBucket> results = new ArrayList<>(reqs.size());
        for (QuotaGrantRequest req : reqs) {
            results.add(provision(req));
        }
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // authorize
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public QuotaDecision authorize(QuotaAuthorizeRequest request) {
        // 1. Validate resource exists in catalog
        if (!resourceCatalogService.isRegistered(request.getResourceCode())) {
            log.warn("[quota] authorize rejected — unregistered resource: {}", request.getResourceCode());
            return QuotaDecision.deny("RESOURCE_NOT_REGISTERED");
        }

        // 2. Idempotency check — same (accountId, idempotencyKey) returns existing decision
        QuotaReservation existing = reservationMapper.selectOne(
                new LambdaQueryWrapper<QuotaReservation>()
                        .eq(QuotaReservation::getAccountId, request.getAccountId())
                        .eq(QuotaReservation::getIdempotencyKey, request.getIdempotencyKey())
        );
        if (existing != null) {
            log.debug("[quota] authorize idempotent hit — reservationCode={}", existing.getReservationCode());
            if (ReservationStatus.ACTIVE.name().equals(existing.getStatus())) {
                BigDecimal remaining = computeRemaining(request.getAccountId(), request.getResourceCode());
                return QuotaDecision.allow(existing.getReservationCode(), remaining);
            }
            // Already committed/released — treat as new request (idempotency_key already consumed)
            // Fall through to create a new reservation
        }

        // 3. Load ACTIVE buckets ordered by consumption priority (P0-10 rules)
        List<QuotaBucket> buckets = listActiveBucketsForReserve(
                request.getAccountId(), request.getResourceCode());

        // 4. Check total available across all buckets
        BigDecimal totalAvailable = buckets.stream()
                .map(QuotaBucket::availableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalAvailable.compareTo(request.getEstimatedQuantity()) < 0) {
            // OSS: HARD_LIMIT only — deny immediately
            // TODO(G2-01): check bucket.overagePolicy; for non-HARD_LIMIT delegate to enterprise impl
            log.info("[quota] authorize DENY — accountId={} resource={} needed={} available={}",
                    request.getAccountId(), request.getResourceCode(),
                    request.getEstimatedQuantity(), totalAvailable);
            return QuotaDecision.deny("INSUFFICIENT_QUOTA");
        }

        // 5. Distribute reservation across buckets (greedy, consumption priority order)
        List<BucketTake> takes = allocateGreedy(buckets, request.getEstimatedQuantity());

        // 6. Apply optimistic-lock updates to each bucket + write lines
        Duration ttl = request.getReservationTtl() != null
                ? request.getReservationTtl()
                : DEFAULT_RESERVATION_TTL;
        String reservationCode = "RES-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();

        // Write reservation header first
        QuotaReservation reservation = QuotaReservation.builder()
                .reservationCode(reservationCode)
                .accountId(request.getAccountId())
                .subscriptionId(request.getSubscriptionId() != null ? request.getSubscriptionId() : 0L)
                .resourceCode(request.getResourceCode())
                .estimatedAmount(request.getEstimatedQuantity())
                .unit(buckets.get(0).getUnit())
                .status(ReservationStatus.ACTIVE.name())
                .idempotencyKey(request.getIdempotencyKey())
                .expiresAt(Instant.now().plus(ttl))
                .build();
        reservationMapper.insert(reservation);

        // Apply per-bucket CAS updates
        BigDecimal reservedTotal = BigDecimal.ZERO;
        for (BucketTake take : takes) {
            applyReserveWithRetry(take.bucket, take.amount);
            // Write reservation line
            QuotaReservationLine line = QuotaReservationLine.builder()
                    .reservationId(reservation.getId())
                    .bucketId(take.bucket.getId())
                    .amount(take.amount)
                    .build();
            reservationLineMapper.insert(line);
            // Write RESERVE ledger entry
            writeLedger(take.bucket, reservation.getId(),
                    OperationType.RESERVE, take.amount.negate(),
                    request.getIdempotencyKey());
            reservedTotal = reservedTotal.add(take.amount);
        }

        BigDecimal remaining = computeRemaining(request.getAccountId(), request.getResourceCode());
        log.info("[quota] authorize ALLOW — reservationCode={} accountId={} resource={} reserved={}",
                reservationCode, request.getAccountId(), request.getResourceCode(), reservedTotal);
        return QuotaDecision.allow(reservationCode, remaining);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // commit
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public QuotaCommitResult commit(String reservationCode, BigDecimal actualQuantity) {
        QuotaReservation reservation = requireActiveReservation(reservationCode);

        // OSS: actual must be <= estimated
        // TODO(G2-02): enterprise impl handles actual > estimated (top-up deduction)
        BigDecimal estimated = reservation.getEstimatedAmount();
        if (actualQuantity.compareTo(estimated) > 0) {
            log.warn("[quota] commit capping actual {} to estimated {} — top-up TODO(G2-02)",
                    actualQuantity, estimated);
            actualQuantity = estimated;
        }
        if (actualQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("actualQuantity must be >= 0, got: " + actualQuantity);
        }

        // Load lines
        List<QuotaReservationLine> lines = reservationLineMapper.selectList(
                new LambdaQueryWrapper<QuotaReservationLine>()
                        .eq(QuotaReservationLine::getReservationId, reservation.getId())
                        .orderByAsc(QuotaReservationLine::getId)
        );
        if (lines.isEmpty()) {
            throw new IllegalStateException("No reservation lines for " + reservationCode);
        }

        // Proportional commit across lines
        BigDecimal totalEstimated = lines.stream()
                .map(QuotaReservationLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal releasedTotal = BigDecimal.ZERO;

        for (QuotaReservationLine line : lines) {
            QuotaBucket bucket = requireBucket(line.getBucketId());
            // Proportional split: lineActual = actualQuantity * (line.amount / totalEstimated)
            BigDecimal lineActual = totalEstimated.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : actualQuantity.multiply(line.getAmount())
                            .divide(totalEstimated, 6, RoundingMode.DOWN);
            BigDecimal lineRelease = line.getAmount().subtract(lineActual);
            releasedTotal = releasedTotal.add(lineRelease);

            applyCommitWithRetry(bucket, lineActual, line.getAmount());
            // COMMIT ledger: used += lineActual
            writeLedger(bucket, reservation.getId(),
                    OperationType.COMMIT, lineActual,
                    reservation.getIdempotencyKey());
            // RELEASE ledger: if there's a delta return
            if (lineRelease.compareTo(BigDecimal.ZERO) > 0) {
                writeLedger(bucket, reservation.getId(),
                        OperationType.RELEASE, lineRelease,
                        reservation.getIdempotencyKey());
            }
        }

        // Update reservation to COMMITTED
        reservation.setStatus(ReservationStatus.COMMITTED.name());
        reservation.setActualAmount(actualQuantity);
        reservationMapper.updateById(reservation);

        BigDecimal remaining = computeRemaining(reservation.getAccountId(), reservation.getResourceCode());
        log.info("[quota] commit — reservationCode={} actual={} released={}",
                reservationCode, actualQuantity, releasedTotal);
        return QuotaCommitResult.builder()
                .reservationCode(reservationCode)
                .actualAmount(actualQuantity)
                .releasedDelta(releasedTotal)
                .remainingAfterCommit(remaining)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // release
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void release(String reservationCode) {
        QuotaReservation reservation = requireActiveReservation(reservationCode);

        List<QuotaReservationLine> lines = reservationLineMapper.selectList(
                new LambdaQueryWrapper<QuotaReservationLine>()
                        .eq(QuotaReservationLine::getReservationId, reservation.getId())
        );

        for (QuotaReservationLine line : lines) {
            QuotaBucket bucket = requireBucket(line.getBucketId());
            applyReleaseWithRetry(bucket, line.getAmount());
            writeLedger(bucket, reservation.getId(),
                    OperationType.RELEASE, line.getAmount(),
                    reservation.getIdempotencyKey());
        }

        reservation.setStatus(ReservationStatus.RELEASED.name());
        reservationMapper.updateById(reservation);
        log.info("[quota] release — reservationCode={}", reservationCode);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listActiveBuckets
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<QuotaBucket> listActiveBuckets(Long accountId, String resourceCode) {
        return quotaBucketMapper.selectList(
                new LambdaQueryWrapper<QuotaBucket>()
                        .eq(QuotaBucket::getAccountId, accountId)
                        .eq(QuotaBucket::getResourceCode, resourceCode)
                        .eq(QuotaBucket::getStatus, STATUS_ACTIVE)
                        .orderByAsc(QuotaBucket::getPriority)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load ACTIVE buckets whose valid period covers {@code now}, then sort them
     * by the multi-key consumption priority defined in {@link BucketConsumptionComparator}:
     * <ol>
     *   <li>Expiry-soon buckets first (threshold from
     *       {@code auraboot.billing.quota.expiry-preempt-days}, default 7).
     *   <li>Within expiring group: period_end ascending (soonest expiry first).
     *   <li>Explicit bucket.priority ascending.
     *   <li>Source-type business order ({@link BucketSourceType#consumptionOrder()}).
     *   <li>Period-end ascending.
     *   <li>Bucket id ascending (stable tie-breaker).
     * </ol>
     */
    private List<QuotaBucket> listActiveBucketsForReserve(Long accountId, String resourceCode) {
        Instant now = Instant.now();
        List<QuotaBucket> buckets = quotaBucketMapper.selectList(
                new LambdaQueryWrapper<QuotaBucket>()
                        .eq(QuotaBucket::getAccountId, accountId)
                        .eq(QuotaBucket::getResourceCode, resourceCode)
                        .eq(QuotaBucket::getStatus, STATUS_ACTIVE)
                        .le(QuotaBucket::getPeriodStart, now)
                        .ge(QuotaBucket::getPeriodEnd, now)
        );
        buckets.sort(new BucketConsumptionComparator(now, billingQuotaProperties.getExpiryPreemptDays()));
        return buckets;
    }

    /**
     * Greedy multi-bucket allocation: consume from buckets in consumption-priority order
     * until the full {@code needed} amount is satisfied.
     *
     * <p>Each participating bucket produces exactly one {@link BucketTake}.
     * Callers write a {@link QuotaReservationLine} for each take.
     */
    private List<BucketTake> allocateGreedy(List<QuotaBucket> buckets, BigDecimal needed) {
        List<BucketTake> takes = new ArrayList<>();
        BigDecimal remaining = needed;
        for (QuotaBucket bucket : buckets) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal available = bucket.availableAmount();
            if (available.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal take = available.min(remaining);
            takes.add(new BucketTake(bucket, take));
            remaining = remaining.subtract(take);
        }
        return takes;
    }

    /**
     * Manual CAS: {@code bucket.reserved += amount}.
     * Uses SQL {@code WHERE id=? AND version=?} so concurrent updates are serialized.
     */
    private void applyReserveWithRetry(QuotaBucket bucket, BigDecimal amount) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            QuotaBucket fresh = attempt == 0 ? bucket : requireBucket(bucket.getId());
            int updated = quotaBucketMapper.casAddReserved(fresh.getId(), amount, fresh.getVersion());
            if (updated == 1) return;
            log.debug("[quota] reserve CAS retry {}/{} bucketId={}", attempt + 1, MAX_RETRY, bucket.getId());
        }
        throw new IllegalStateException("Optimistic lock failed after " + MAX_RETRY + " retries on bucket " + bucket.getId());
    }

    /**
     * Manual CAS: {@code bucket.used += actual}, {@code bucket.reserved -= lineReserved}.
     * GREATEST(0,…) in SQL enforces non-negative invariant.
     */
    private void applyCommitWithRetry(QuotaBucket bucket, BigDecimal actual, BigDecimal lineReserved) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            QuotaBucket fresh = attempt == 0 ? bucket : requireBucket(bucket.getId());
            int updated = quotaBucketMapper.casCommit(fresh.getId(), actual, lineReserved, fresh.getVersion());
            if (updated == 1) return;
            log.debug("[quota] commit CAS retry {}/{} bucketId={}", attempt + 1, MAX_RETRY, bucket.getId());
        }
        throw new IllegalStateException("Optimistic lock failed after " + MAX_RETRY + " retries on bucket " + bucket.getId());
    }

    /**
     * Manual CAS: {@code bucket.reserved -= amount} (release).
     * GREATEST(0,…) in SQL enforces non-negative invariant.
     */
    private void applyReleaseWithRetry(QuotaBucket bucket, BigDecimal amount) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            QuotaBucket fresh = attempt == 0 ? bucket : requireBucket(bucket.getId());
            int updated = quotaBucketMapper.casSubtractReserved(fresh.getId(), amount, fresh.getVersion());
            if (updated == 1) return;
            log.debug("[quota] release CAS retry {}/{} bucketId={}", attempt + 1, MAX_RETRY, bucket.getId());
        }
        throw new IllegalStateException("Optimistic lock failed after " + MAX_RETRY + " retries on bucket " + bucket.getId());
    }

    /**
     * Write a single ledger entry. The {@code amount} is the absolute delta
     * (always positive) — the operation type encodes the direction semantics.
     */
    private void writeLedger(QuotaBucket bucket, Long reservationId,
                              OperationType opType, BigDecimal amount, String idempotencyKey) {
        // Re-read bucket to get the latest available balance
        QuotaBucket fresh = requireBucket(bucket.getId());
        BigDecimal balanceAfter = fresh.availableAmount();

        QuotaLedger entry = QuotaLedger.builder()
                .ledgerCode("LED-" + UUID.randomUUID().toString().replace("-", "").toUpperCase())
                .bucketId(bucket.getId())
                .reservationId(reservationId)
                .accountId(bucket.getAccountId())
                .subscriptionId(bucket.getSubscriptionId())
                .operationType(opType.name())
                .amount(amount.abs())
                .balanceAfter(balanceAfter)
                .idempotencyKey(idempotencyKey)
                .occurredAt(Instant.now())
                .build();
        ledgerMapper.insert(entry);
    }

    /** Compute total available across all ACTIVE buckets (for remaining-balance reporting). */
    private BigDecimal computeRemaining(Long accountId, String resourceCode) {
        return listActiveBuckets(accountId, resourceCode).stream()
                .map(QuotaBucket::availableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private QuotaReservation requireActiveReservation(String reservationCode) {
        QuotaReservation r = reservationMapper.selectOne(
                new LambdaQueryWrapper<QuotaReservation>()
                        .eq(QuotaReservation::getReservationCode, reservationCode)
        );
        if (r == null) {
            throw new IllegalArgumentException("Reservation not found: " + reservationCode);
        }
        if (!ReservationStatus.ACTIVE.name().equals(r.getStatus())) {
            throw new IllegalArgumentException(
                    "Reservation is not ACTIVE (status=" + r.getStatus() + "): " + reservationCode);
        }
        return r;
    }

    private QuotaBucket requireBucket(Long bucketId) {
        QuotaBucket b = quotaBucketMapper.selectById(bucketId);
        if (b == null) throw new IllegalStateException("Bucket not found: " + bucketId);
        return b;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal value object
    // ─────────────────────────────────────────────────────────────────────────

    private record BucketTake(QuotaBucket bucket, BigDecimal amount) {}
}
