package com.auraboot.framework.meta.constant;

/**
 * Event delivery status for the Outbox Pattern.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public enum OutboxStatus {

    /** Written, awaiting pickup */
    PENDING,

    /** Being dispatched by worker */
    PROCESSING,

    /** Successfully dispatched */
    DELIVERED,

    /** Exceeded max retries (dead letter) */
    FAILED
}
