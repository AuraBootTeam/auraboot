package com.auraboot.framework.connector.saas.http;

/**
 * Single-shot HTTP executor SPI. Production wiring uses
 * {@link JdkHttpExecutor} (java.net.http.HttpClient); tests substitute an
 * in-memory recorder so they don't need a real port or WireMock.
 */
public interface SaasHttpExecutor {

    /**
     * Execute exactly one request and return the response. Implementations
     * MUST NOT retry — retry / rate-limit policy lives in {@link SaasHttpClient}
     * so it can be exercised end-to-end without a real network.
     *
     * @throws SaasHttpException when the transport layer fails outright
     *         (connection refused, DNS, TLS); a non-2xx HTTP status is
     *         <em>not</em> an exception — it returns as a normal response.
     */
    SaasHttpResponse execute(SaasHttpRequest request);
}
