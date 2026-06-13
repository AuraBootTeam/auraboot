package com.auraboot.framework.billing.quota.model;

/**
 * Whether a quota pool is exclusively assigned or shared among multiple consumers.
 *
 * <p>Values MUST match the {@code chk_billing_pool_type} CHECK constraint
 * in {@code 2026-06-10-billing-quota.sql} exactly.
 *
 * <p>OSS base impl only wires {@link #DEDICATED} single-pool lookup.
 * Shared-pool routing/fan-out is TODO gap G5 (enterprise).
 */
public enum PoolType {
    /** Pool is exclusively assigned to one account/subscription. */
    DEDICATED,
    /**
     * Pool is shared across multiple consumers (e.g. platform-wide free tier).
     * TODO: enterprise shared-pool routing (gap G5).
     */
    SHARED
}
