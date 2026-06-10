package com.auraboot.framework.billing.quota.model;

/**
 * Scope at which a quota pool applies.
 *
 * <p>Values MUST match the {@code chk_billing_pool_scope_type} CHECK constraint
 * in {@code 2026-06-10-billing-quota.sql} exactly.
 */
public enum ScopeType {
    /** Quota applies to the entire account. */
    ACCOUNT,
    /** Quota is per-user within the account. */
    USER,
    /** Quota is shared within a team. */
    TEAM,
    /** Quota is tied to a specific subscription. */
    SUBSCRIPTION,
    /** Quota is shared within a custom group. */
    GROUP
}
