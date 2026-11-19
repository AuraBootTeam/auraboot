package com.auraboot.framework.plugin.handover;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Stable, precision-preserving fingerprints; never serializes a public live-resource response. */
public final class HandoverJson {
    private HandoverJson() {}
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    public static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            var keys = new TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, canonical(node.get(key)))); return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode(); node.forEach(value -> result.add(canonical(value))); return result;
        }
        return node.deepCopy();
    }
    public static String digest(Object value) {
        try {
            byte[] data = JSON.writeValueAsString(canonical(JSON.valueToTree(value))).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.io.IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint handover metadata", failure);
        }
    }
}
