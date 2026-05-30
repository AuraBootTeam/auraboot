package com.auraboot.framework.connector.saas.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Production {@link SaasHttpExecutor} backed by {@link java.net.http.HttpClient}.
 * Single bean — shared connection pool across all vendor adapters because the
 * JDK HttpClient pool is keyed by destination authority.
 *
 * <p>Per-request {@code connectTimeoutMs} / {@code readTimeoutMs} come from
 * the {@link SaasHttpRequest}; the underlying {@link HttpClient#connectTimeout()}
 * is set once at builder time to the request's connect timeout via a per-call
 * builder copy.
 */
@Slf4j
@Component
public class JdkHttpExecutor implements SaasHttpExecutor {

    private final ObjectMapper jsonMapper;
    private final HttpClient httpClient;

    @Autowired
    public JdkHttpExecutor(ObjectMapper jsonMapper) {
        this(jsonMapper, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** Test seam. */
    JdkHttpExecutor(ObjectMapper jsonMapper, HttpClient httpClient) {
        this.jsonMapper = jsonMapper;
        this.httpClient = httpClient;
    }

    @Override
    public SaasHttpResponse execute(SaasHttpRequest req) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(req.url()))
                .timeout(Duration.ofMillis(req.readTimeoutMs()));
        for (Map.Entry<String, String> h : req.headers().entrySet()) {
            b.header(h.getKey(), h.getValue());
        }
        HttpRequest.BodyPublisher publisher;
        if (req.body() == null) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else if (req.body() instanceof byte[] bytes) {
            publisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
        } else if (req.body() instanceof CharSequence cs) {
            publisher = HttpRequest.BodyPublishers.ofString(cs.toString());
        } else {
            try {
                byte[] bytes = jsonMapper.writeValueAsBytes(req.body());
                publisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
                if (!req.headers().containsKey("Content-Type")) {
                    b.header("Content-Type", "application/json");
                }
            } catch (Exception e) {
                throw new SaasHttpException("Failed to serialise JSON request body: "
                        + e.getMessage(), e);
            }
        }
        b.method(req.method(), publisher);

        try {
            HttpResponse<byte[]> resp = httpClient.send(b.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return new SaasHttpResponse(resp.statusCode(), resp.headers().map(), resp.body());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new SaasHttpException("HTTP timeout after " + req.readTimeoutMs() + "ms: "
                    + req.method() + " " + req.url(), e);
        } catch (java.net.ConnectException e) {
            throw new SaasHttpException("Connection refused: " + req.url(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SaasHttpException("Interrupted: " + req.url(), e);
        } catch (java.io.IOException e) {
            throw new SaasHttpException("I/O failure: " + e.getMessage()
                    + " (" + req.method() + " " + req.url() + ")", e);
        }
    }
}
