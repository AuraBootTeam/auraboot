package com.auraboot.framework.billing.quota.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the OSS quota engine.
 *
 * <p>Bound from the {@code auraboot.billing.quota} namespace in
 * {@code application.yml} (or any profile-specific override).
 *
 * <p>Example override:
 * <pre>
 * auraboot:
 *   billing:
 *     quota:
 *       expiry-preempt-days: 14
 * </pre>
 */
@ConfigurationProperties(prefix = "auraboot.billing.quota")
public class BillingQuotaProperties {

    /**
     * Number of days before a bucket's {@code period_end} at which it is
     * considered "expiring soon" and promoted to the front of the consumption
     * queue regardless of source-type order.
     *
     * <p>Default: 7 days.
     */
    private int expiryPreemptDays = 7;

    public int getExpiryPreemptDays() {
        return expiryPreemptDays;
    }

    public void setExpiryPreemptDays(int expiryPreemptDays) {
        this.expiryPreemptDays = expiryPreemptDays;
    }
}
