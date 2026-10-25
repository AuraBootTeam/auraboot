package com.auraboot.framework.agent.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON SHA-256 hasher — single source of truth for Shadow Mode
 * output-hash comparisons.
 *
 * <p>The original implementations diverged: {@code ShadowExecutor} used
 * Jackson {@code writeValueAsString} against a list of {@code HashMap}s
 * (unstable key order → non-deterministic hash), while
 * {@code ShadowRunScheduler} used Postgres {@code md5(after_snapshot::text)}
 * over the raw column bytes. The two could never match, so
 * {@code output_match} was always {@code false} and no draft ever
 * auto-promoted.
 *
 * <p>This utility unifies both sides:
 * <ul>
 *   <li>Recursively sorts every {@link Map} by key (deep, stable).</li>
 *   <li>Serialises with {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}.</li>
 *   <li>SHA-256 + lower-case hex.</li>
 *   <li>Returns {@code null} (not empty string) on failure so callers can
 *       distinguish a failed hash from a match against another failed hash.</li>
 * </ul>
 */
public final class CanonicalJsonHasher {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final TypeReference<Object> ANY = new TypeReference<>() {};

    private CanonicalJsonHasher() {}

    /**
     * Hash an in-memory payload. Maps are deep-sorted by key before
     * serialization so {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}}
     * hash identically.
     *
     * @return lowercase hex SHA-256, or {@code null} if payload is null
     *         or serialization fails.
     */
    public static String sha256Canonical(Object payload) {
        if (payload == null) return null;
        try {
            Object sorted = sortDeep(payload);
            String json = MAPPER.writeValueAsString(sorted);
            return sha256Hex(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Hash a raw JSON string by first re-parsing it into a tree and then
     * serializing canonically. This lets us compare JSON produced outside
     * the JVM (e.g. Postgres {@code after_snapshot::text}) against JSON
     * built in Java using {@link #sha256Canonical(Object)}.
     *
     * @return lowercase hex SHA-256, or {@code null} if the input is null,
     *         blank, or not valid JSON.
     */
    public static String sha256CanonicalJsonString(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Object parsed = MAPPER.readValue(json, ANY);
            return sha256Canonical(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sortDeep(Object value) {
        if (value instanceof Map<?, ?> m) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                // Key as String — Jackson writes non-String map keys via toString anyway.
                sorted.put(String.valueOf(e.getKey()), sortDeep(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object item : l) out.add(sortDeep(item));
            return out;
        }
        return value;
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
