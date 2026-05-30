package com.auraboot.framework.connector.saas.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Vendor-agnostic HTTP request envelope passed to {@link SaasHttpClient}.
 *
 * <p>{@code vendor} keys both the rate-limiter bucket and audit row; it must
 * be the same value as {@link com.auraboot.framework.connector.sdk.ConnectorDescriptor#vendor()}
 * to keep tenant-level metrics consistent.
 *
 * <p>{@code tenantId} drives the per-tenant per-vendor bucket so a noisy
 * tenant cannot starve quieter tenants on the same connector.
 *
 * <p>{@code body} is serialised to JSON by the executor when non-null; pass
 * an empty map for an explicit empty body, {@code null} for "no body".
 */
public final class SaasHttpRequest {

    private final Long tenantId;
    private final String vendor;
    private final String method;
    private final String url;
    private final Map<String, String> headers;
    private final Object body;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private SaasHttpRequest(Builder b) {
        this.tenantId = b.tenantId;
        this.vendor = Objects.requireNonNull(b.vendor, "vendor");
        this.method = Objects.requireNonNull(b.method, "method").toUpperCase();
        this.url = Objects.requireNonNull(b.url, "url");
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.body = b.body;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs = b.readTimeoutMs;
    }

    public Long tenantId() { return tenantId; }
    public String vendor() { return vendor; }
    public String method() { return method; }
    public String url() { return url; }
    public Map<String, String> headers() { return headers; }
    public Object body() { return body; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long tenantId;
        private String vendor;
        private String method = "GET";
        private String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Object body;
        private int connectTimeoutMs = 10_000;
        private int readTimeoutMs = 30_000;

        public Builder tenantId(Long v) { this.tenantId = v; return this; }
        public Builder vendor(String v) { this.vendor = v; return this; }
        public Builder method(String v) { this.method = v; return this; }
        public Builder url(String v) { this.url = v; return this; }
        public Builder header(String k, String v) { this.headers.put(k, v); return this; }
        public Builder bearer(String token) { return header("Authorization", "Bearer " + token); }
        public Builder body(Object v) { this.body = v; return this; }
        public Builder connectTimeoutMs(int v) { this.connectTimeoutMs = v; return this; }
        public Builder readTimeoutMs(int v) { this.readTimeoutMs = v; return this; }
        public SaasHttpRequest build() { return new SaasHttpRequest(this); }
    }
}
