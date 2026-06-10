package com.auraboot.framework.billing.quota.model;

/**
 * Policy applied when a quota bucket is exhausted.
 *
 * <p>Values MUST match the {@code chk_billing_bucket_overage_policy} CHECK constraint
 * in {@code 2026-06-10-billing-quota.sql} exactly.
 *
 * <p>OSS base impl only enforces {@link #HARD_LIMIT}.  All other values are
 * stored/validated but the enforcement logic is delegated to the enterprise
 * {@code QuotaService} implementation (TODO gap G2-01 / G5).
 */
public enum OveragePolicy {
    /**
     * Deny the request when quota is exhausted.
     * Implemented by OSS {@code QuotaServiceImpl}.
     */
    HARD_LIMIT,
    /**
     * Allow overage but flag it; enforcement/billing happens asynchronously.
     * TODO: enterprise impl (gap G2-01).
     */
    SOFT_LIMIT,
    /**
     * Rate-limit the caller instead of hard-denying.
     * TODO: enterprise impl (gap G5).
     */
    THROTTLE,
    /**
     * Downgrade feature access when quota is exhausted.
     * TODO: enterprise impl (gap G5).
     */
    DOWNGRADE,
    /**
     * Allow overage and generate an overage charge line item.
     * TODO: enterprise impl (gap G2-01).
     */
    OVERAGE_CHARGE,
    /**
     * Allow overage but send notification only; no enforcement.
     * TODO: enterprise impl (gap G5).
     */
    NOTIFY_ONLY
}
