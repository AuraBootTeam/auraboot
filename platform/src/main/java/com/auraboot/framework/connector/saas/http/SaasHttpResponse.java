package com.auraboot.framework.connector.saas.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Vendor-agnostic HTTP response. JSON-shaped responses are exposed via
 * {@link #json(ObjectMapper)}; non-JSON bodies stay as raw bytes.
 *
 * <p>{@code retryAfterSeconds} pulls from the {@code Retry-After} header when
 * present so the {@link SaasHttpClient} retry loop can honour the vendor's
 * back-off hint instead of hammering with exponential jitter.
 */
public final class SaasHttpResponse {

    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    public SaasHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        this.statusCode = statusCode;
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.body = body == null ? new byte[0] : body;
    }

    public int statusCode() { return statusCode; }
    public Map<String, List<String>> headers() { return headers; }
    public byte[] body() { return body; }

    public String header(String name) {
        if (name == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())
                    && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    public Optional<Integer> retryAfterSeconds() {
        String v = header("Retry-After");
        if (v == null || v.isBlank()) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public boolean isRetryable() {
        return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    public String bodyAsString() {
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    public JsonNode json(ObjectMapper mapper) {
        if (body.length == 0) return mapper.nullNode();
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new SaasHttpException("Failed to parse JSON response: " + e.getMessage()
                    + " (status=" + statusCode + ", preview="
                    + truncate(bodyAsString(), 200) + ")", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Convenience: read the value of {@code header} as a lowercase Locale-independent string.
     */
    public String headerLower(String name) {
        String v = header(name);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }
}
