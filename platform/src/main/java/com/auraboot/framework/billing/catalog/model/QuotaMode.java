package com.auraboot.framework.billing.catalog.model;

/**
 * How a quota for a resource is managed / reset.
 *
 * <p>Values are stored verbatim in {@code ab_billing_resource_catalog.quota_mode}.
 * Keep in sync with the CHECK constraint in
 * {@code 2026-06-10-billing-resource-catalog.sql}.
 */
public enum QuotaMode {

    /** Quota represents a maximum stock at any given time (e.g. seat count). */
    STOCK,

    /** Quota is reset periodically (e.g. monthly AI token allowance). */
    PERIODIC,

    /** Quota is expressed as a rate limit (e.g. requests/second). */
    RATE,

    /** Resource is controlled by entitlement flag rather than a numeric quota. */
    ENTITLEMENT,

    /** Quota is tied to a self-hosted license (seat/node) issued externally. */
    LICENSE
}
