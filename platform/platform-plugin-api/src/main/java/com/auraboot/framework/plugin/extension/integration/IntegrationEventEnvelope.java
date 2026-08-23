package com.auraboot.framework.plugin.extension.integration;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned cross-domain event envelope owned by the AuraBoot integration runtime.
 *
 * <p>The event type is a stable public contract code such as
 * {@code procurement.purchase-order.receipt-requested.v1}; it must not be a JVM class name.
 * Ordering is scoped to {@code tenantId + orderingKey}. Consumers must remain idempotent because
 * delivery is at-least-once.
 */
public record IntegrationEventEnvelope(
        String schemaVersion,
        String eventId,
        String eventType,
        String source,
        String subject,
        Instant occurredAt,
        long tenantId,
        String correlationId,
        String causationId,
        String orderingKey,
        long sequence,
        Map<String, Object> payload,
        Map<String, String> headers
) {

    public static final String VERSION_1 = "1.0";

    public IntegrationEventEnvelope {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        eventId = requireText(eventId, "eventId");
        eventType = requireText(eventType, "eventType");
        source = requireText(source, "source");
        subject = requireText(subject, "subject");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        correlationId = requireText(correlationId, "correlationId");
        orderingKey = requireText(orderingKey, "orderingKey");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
