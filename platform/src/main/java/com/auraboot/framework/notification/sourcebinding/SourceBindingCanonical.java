package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

final class SourceBindingCanonical {
    private static final ObjectMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private SourceBindingCanonical() {
    }
    static JsonNode tree(Object value) {
        return JSON.valueToTree(value);
    }
    static JsonNode parse(String value) {
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            /* CATCH: expected JSON validation failure; sanitize input diagnostics and always reject. */
            throw new IllegalArgumentException("NOTIFICATION_SOURCE_JSON_INVALID");
        }
    }
    static String json(Object value) {
        try {
            return JSON.writeValueAsString(sorted(tree(value)));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            /* CATCH: expected JSON validation failure; sanitize input diagnostics and always reject. */
            throw new IllegalArgumentException("NOTIFICATION_SOURCE_JSON_INVALID");
        }
    }
    static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            keys.forEach(k -> out.set(k, sorted(node.get(k))));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            node.forEach(n -> out.add(sorted(n)));
            return out;
        }
        return node;
    }
    static FieldFeatureBean feature(JsonNode node) {
        try {
            return JSON.treeToValue(node, FieldFeatureBean.class);
        } catch (JsonProcessingException e) {
            /* CATCH: expected typed metadata validation error; never permit fallback or expose raw JSON. */
            throw new IllegalArgumentException("NOTIFICATION_SOURCE_FEATURE_INVALID");
        }
    }
    static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    static String reference(String binding, String source, long user) {
        SourceBindingTypes.code(binding);
        SourceBindingTypes.pid(source);
        SourceBindingTypes.require(user> 0);
        return "ns1." + Base64.getUrlEncoder().withoutPadding().encodeToString(json(List.of(binding, source, user)).getBytes(StandardCharsets.UTF_8));
    }
    record Locator(String binding, String source, long user) {
    }
    static Locator locator(String ref) {
        SourceBindingTypes.require(ref != null && ref.startsWith("ns1.") && ref.length() <= 400);
        try {
            JsonNode n = parse(new String(Base64.getUrlDecoder().decode(ref.substring(4)), StandardCharsets.UTF_8));
            SourceBindingTypes.require(n.isArray() && n.size() == 3 && n.get(0).isTextual() && n.get(1).isTextual() && n.get(2).isIntegralNumber() && n.get(2).canConvertToLong());
            var l = new Locator(n.get(0).textValue(), n.get(1).textValue(), n.get(2).longValue());
            SourceBindingTypes.require(reference(l.binding, l.source, l.user).equals(ref));
            return l;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("NOTIFICATION_SOURCE_REFERENCE_INVALID");
        }
    }
}
