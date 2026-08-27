package com.auraboot.framework.plugin.extension.integration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
        payload = immutableJsonObject(Objects.requireNonNull(payload, "payload"), "payload");
        headers = immutableHeaders(headers);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Map<String, Object> immutableJsonObject(Map<?, ?> source, String path) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(path + " must contain only string keys");
            }
            copy.put(key, immutableJsonValue(entry.getValue(), path + "." + key));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return immutableJsonObject(map, path);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                copy.add(immutableJsonValue(list.get(index), path + "[" + index + "]"));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException(path + " contains a non-JSON value: "
                + value.getClass().getName());
    }

    private static Map<String, String> immutableHeaders(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("headers must contain only non-null string keys");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
