package com.auraboot.framework.billing.quota.spi;

import com.auraboot.framework.billing.quota.model.QuotaBucket;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SPI for the Quota subsystem — reserve, commit, and release quota from buckets.
 *
 * <p>Enterprise or plugin modules may replace the default DB-backed OSS implementation
 * ({@link com.auraboot.framework.billing.quota.service.QuotaServiceImpl}) with a
 * higher-fidelity version that supports additional {@link
 * com.auraboot.framework.billing.quota.model.OveragePolicy} variants,
 * shared-pool routing, and consumption-priority strategies.
 *
 * <h3>Lifecycle of a quota request</h3>
 * <pre>
 *   caller                        QuotaService
 *     │──authorize(req)──────────────►│  check available, write reservation + lines + ledger
 *     │◄──── QuotaDecision(ALLOW) ────│
 *     │                               │
 *     │  (perform the actual work)    │
 *     │                               │
 *     │──commit(code, actual)────────►│  finalize used/reserved, write COMMIT ledger
 *     │◄──── QuotaCommitResult ───────│
 *
 *   — OR —
 *     │──release(code)───────────────►│  restore reserved, write RELEASE ledger
 * </pre>
 *
 * <h3>Idempotency</h3>
 * <p>Repeated {@code authorize} calls with the same {@code (accountId, idempotencyKey)}
 * return the original {@link QuotaDecision} without additional deduction.
 *
 * <h3>Concurrency</h3>
 * <p>Bucket updates use optimistic locking ({@code @Version}).  The OSS impl retries
 * up to {@value com.auraboot.framework.billing.quota.service.QuotaServiceImpl#MAX_RETRY}
 * times before throwing.
 */
public interface QuotaService {

    /**
     * Pre-authorize estimated quota consumption.
     *
     * <p>If sufficient quota is available, a reservation is created and the bucket's
     * {@code reservedAmount} is incremented.  The caller must later call
     * {@link #commit} or {@link #release}.
     *
     * <p>OSS base impl: only {@link com.auraboot.framework.billing.quota.model.OveragePolicy#HARD_LIMIT}
     * enforced — returns DENY when available {@literal <} estimated.
     * All other policies are accepted in the DB but treated as HARD_LIMIT (logged + TODO gap G2-01).
     *
     * @param request authorize parameters
     * @return decision with outcome=ALLOW (reservation_code) or outcome=DENY (reason)
     */
    QuotaDecision authorize(QuotaAuthorizeRequest request);

    /**
     * Commit a reservation with the actual quantity consumed.
     *
     * <p>{@code actualQuantity} must be {@literal ≤} the estimated amount on the OSS
     * base impl.  Actual {@literal >} estimated requires enterprise overage handling
     * (TODO gap G2-02).
     *
     * <p>Effect per reservation line:
     * <ul>
     *   <li>{@code used += proportional_actual}
     *   <li>{@code reserved -= line.amount}
     *   <li>COMMIT + RELEASE-delta ledger entries written
     * </ul>
     *
     * @param reservationCode code returned from {@link #authorize}
     * @param actualQuantity  actual quantity consumed (must be {@literal ≤} estimated)
     * @return commit result with amount and remaining balance
     * @throws IllegalArgumentException if reservation not found or already closed
     */
    QuotaCommitResult commit(String reservationCode, java.math.BigDecimal actualQuantity);

    /**
     * Release a reservation without committing (cancellation / timeout / error).
     *
     * <p>Restores the reserved amount back to the bucket's available balance.
     *
     * @param reservationCode code returned from {@link #authorize}
     * @throws IllegalArgumentException if reservation not found or already closed
     */
    void release(String reservationCode);

    /**
     * Return all ACTIVE buckets for the given account and resource, ordered by
     * priority ascending (lower = consumed first).
     *
     * <p>Intended for read-only balance display; callers must not modify the returned
     * entities directly.
     *
     * @param accountId    account identifier
     * @param resourceCode resource type code
     * @return ordered list of active buckets; empty if no quota configured
     */
    List<QuotaBucket> listActiveBuckets(Long accountId, String resourceCode);

    // ─────────────────────────────────────────────────────────────────────────
    // Provision (GRANT)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Provision (grant) a new quota bucket for an account/subscription.
     *
     * <p>This is the subscription→quota-bucket seam: when a subscription is activated
     * the billing layer calls {@code provision} for each resource entitlement, and the
     * resulting bucket becomes the target for future {@link #authorize} calls.
     *
     * <h3>What this does</h3>
     * <ol>
     *   <li>Validates {@code resourceCode} via {@code ResourceCatalogService.isRegistered}.
     *   <li>Idempotency check: if a bucket was already provisioned with the same
     *       {@code idempotencyKey} for this account, returns the original bucket
     *       without creating duplicates.
     *   <li>Finds or creates a {@code quota_pool} for
     *       {@code (accountId, resourceCode, scopeType=ACCOUNT, poolType=DEDICATED)}.
     *   <li>Creates a new {@code quota_bucket} with
     *       {@code total=amount, used=0, reserved=0, status=ACTIVE, version=0}.
     *   <li>Writes a single {@code GRANT} ledger entry with
     *       {@code amount=req.amount, balance_after=req.amount}.
     * </ol>
     *
     * <p>All steps execute in a single transaction.
     *
     * @param req grant parameters (see {@link QuotaGrantRequest})
     * @return the newly created bucket, or the existing one on idempotent replay
     * @throws IllegalArgumentException if {@code resourceCode} is not registered,
     *                                  or {@code amount} is not positive
     */
    QuotaBucket provision(QuotaGrantRequest req);

    /**
     * Batch-provision multiple quota buckets in a single transaction.
     *
     * <p>Useful when activating a subscription that covers several resource types
     * (e.g. AI_TOKEN + API_CALL + STORAGE_GB in one go).  Each request is processed
     * independently — one failure rolls back the entire batch.
     *
     * <p>Each element uses its own {@link QuotaGrantRequest#getIdempotencyKey()} for
     * deduplication; already-provisioned buckets are returned as-is.
     *
     * @param reqs list of grant requests (must not be null; may be empty)
     * @return list of buckets in the same order as the input requests
     * @throws IllegalArgumentException if any request fails validation
     */
    default List<QuotaBucket> provisionAll(List<QuotaGrantRequest> reqs) {
        return reqs.stream().map(this::provision).collect(Collectors.toList());
    }
}
