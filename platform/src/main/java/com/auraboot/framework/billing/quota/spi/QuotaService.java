package com.auraboot.framework.billing.quota.spi;

import com.auraboot.framework.billing.quota.model.QuotaBucket;

import java.util.List;

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
}
