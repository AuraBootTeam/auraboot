package com.auraboot.framework.behavior.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable query identity without retaining SQL, filters or result rows in telemetry. */
public final class AnalyticsQueryFingerprint {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private AnalyticsQueryFingerprint() { }

    public static String of(JsonNode query) {
        if (query == null || !query.isObject()) {
            throw new IllegalArgumentException("Analytics query must be an object");
        }
        try {
            byte[] canonical = MAPPER.writeValueAsBytes(MAPPER.convertValue(query, Object.class));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot fingerprint analytics query", error);
        }
    }
}
