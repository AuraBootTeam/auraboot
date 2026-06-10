package com.auraboot.framework.billing.quota.spi;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Input to {@link QuotaService#authorize}.
 *
 * <p>Callers supply the account context, resource, estimated quantity, and a
 * caller-controlled idempotency key.  Repeated calls with the same
 * {@code (accountId, idempotencyKey)} return the original decision without
 * double-deducting (idempotent authorize).
 */
@Value
@Builder
public class QuotaAuthorizeRequest {

    /** Account for which quota is being checked. */
    Long accountId;

    /** Linked subscription (used to look up the correct pool). */
    Long subscriptionId;

    /** Resource type — must be registered in {@code ab_billing_resource_catalog}. */
    String resourceCode;

    /** Estimated quantity the caller needs to pre-authorize. */
    BigDecimal estimatedQuantity;

    /**
     * Caller-supplied idempotency key.
     * Recommended: {@code "<caller_request_id>"} or UUID.
     * Max 128 chars.
     */
    String idempotencyKey;

    /**
     * How long the reservation should be held before auto-expiry.
     * Defaults to 30 minutes if null.
     */
    Duration reservationTtl;
}
