package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory {@link PluginHttpResponse} used by the gamma-2 governed pipeline. A plugin handler
 * writes status / headers / body into this buffer <em>inside</em> the request transaction; only
 * after the transaction commits does {@link RestEndpointPipeline} flush it to the real servlet
 * response. If the handler throws (or the commit fails) nothing is flushed, so the dispatcher can
 * still emit a clean error status — impossible with the write-through {@link ServletPluginHttpResponse}.
 *
 * <p>Also serializes to / from a flat map ({@link #toOutcomeMap()} / {@link #fromOutcomeMap(Map)})
 * so an idempotent route can cache its response via the platform {@code IdempotencyService} and
 * replay it byte-for-byte.
 */
public class BufferingPluginHttpResponse implements PluginHttpResponse {

    private int status = 200;
    private String contentType;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    @Override
    public PluginHttpResponse status(int code) {
        this.status = code;
        return this;
    }

    @Override
    public PluginHttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    @Override
    public PluginHttpResponse contentType(String mediaType) {
        this.contentType = mediaType;
        return this;
    }

    @Override
    public OutputStream out() {
        return body;
    }

    public int status() {
        return status;
    }

    public String contentType() {
        return contentType;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public byte[] body() {
        return body.toByteArray();
    }

    /** Replace the buffered body wholesale (used by replay reconstruction). */
    public void bodyBytes(byte[] bytes) {
        body.reset();
        if (bytes != null) {
            try {
                body.write(bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Copy the buffered status / headers / content-type / body into the real servlet response. */
    public void flushTo(HttpServletResponse servlet) {
        servlet.setStatus(status);
        if (contentType != null) {
            servlet.setContentType(contentType);
        }
        headers.forEach(servlet::setHeader);
        try {
            servlet.getOutputStream().write(body.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── Idempotent replay serialization ──────────────────────────────────────

    private static final String K_STATUS = "status";
    private static final String K_CONTENT_TYPE = "contentType";
    private static final String K_HEADERS = "headers";
    private static final String K_BODY_B64 = "bodyBase64";

    /** Flatten to a JSON-serializable map for the idempotency ledger. */
    public Map<String, Object> toOutcomeMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(K_STATUS, status);
        out.put(K_CONTENT_TYPE, contentType);
        out.put(K_HEADERS, new LinkedHashMap<>(headers));
        out.put(K_BODY_B64, Base64.getEncoder().encodeToString(body.toByteArray()));
        return out;
    }

    /** Rebuild a response from a previously cached idempotency outcome. */
    @SuppressWarnings("unchecked")
    public static BufferingPluginHttpResponse fromOutcomeMap(Map<String, Object> outcome) {
        BufferingPluginHttpResponse buf = new BufferingPluginHttpResponse();
        Object st = outcome.get(K_STATUS);
        buf.status = st instanceof Number ? ((Number) st).intValue() : 200;
        buf.contentType = (String) outcome.get(K_CONTENT_TYPE);
        Object hdrs = outcome.get(K_HEADERS);
        if (hdrs instanceof Map) {
            ((Map<String, Object>) hdrs).forEach((k, v) -> buf.headers.put(k, v == null ? null : v.toString()));
        }
        Object b64 = outcome.get(K_BODY_B64);
        if (b64 instanceof String s && !s.isEmpty()) {
            buf.bodyBytes(Base64.getDecoder().decode(s));
        }
        return buf;
    }

    @Override
    public String toString() {
        return "BufferingPluginHttpResponse{status=" + status + ", contentType=" + contentType
                + ", bytes=" + body.size() + ", body=" + new String(body.toByteArray(), StandardCharsets.UTF_8) + "}";
    }
}
