package com.auraboot.framework.plugin.extension.integration;

import org.pf4j.ExtensionPoint;

import java.util.Set;

/**
 * A synchronous, transactional consumer of reliable cross-domain events.
 *
 * <p>The host invokes {@link #consume(IntegrationEventEnvelope)} and writes the idempotency receipt
 * in one transaction. Throwing rolls back both the consumer's business writes and the receipt.
 * Implementations must not start an independent transaction or swallow failures.
 */
public interface ReliableEventConsumerExtension extends ExtensionPoint {

    /** Stable consumer identity used by the unique receipt fence. */
    String consumerCode();

    /** Stable event contract codes consumed by this handler. */
    Set<String> subscribedEventTypes();

    /** Apply the event using the fact owner's public command or write service. */
    void consume(IntegrationEventEnvelope envelope);

    default boolean supports(String eventType) {
        return subscribedEventTypes().contains(eventType);
    }
}
