package com.auraboot.framework.rag.service;

import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Embedding service that calls OpenAI-compatible /v1/embeddings API.
 * Resolves provider config from CloudConfig (service_type='embedding').
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final CloudConfigService cloudConfigService;
    private final ObjectMapper objectMapper;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final int BATCH_SLEEP_MS = 200;

    /**
     * Embed a single text string.
     *
     * @return float array of embedding dimensions, or null on failure
     */
    public float[] embed(Long tenantId, String text, String providerCode) {
        List<float[]> results = embedBatch(tenantId, List.of(text), providerCode);
        return results.isEmpty() ? null : results.get(0);
    }

    /** Vector with the identity of the configuration snapshot actually used for its request. */
    public record EmbeddedVector(float[] values, String space) {
        public EmbeddedVector {
            if (values == null || values.length == 0 || space == null || !space.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("A vector and SHA-256 space identity are required");
            }
            values = values.clone();
            double norm = 0;
            for (float value : values) {
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Embedding values must be finite");
                norm += (double) value * value;
            }
            if (norm == 0) throw new IllegalArgumentException("A cosine embedding must be nonzero");
        }
        @Override public float[] values() { return values.clone(); }
    }

    /** Resolve identity without performing a provider request or exposing its credentials. */
    public String currentSpace(Long tenantId, String providerCode, int actualDimensions) {
        EmbeddingConfig config = resolveConfig(tenantId, providerCode);
        return config == null ? null : spaceIdentity(config, actualDimensions);
    }

    public EmbeddedVector embedWithIdentity(Long tenantId, String text, String providerCode) {
        if (text == null || text.isBlank()) return null;
        EmbeddingConfig config = resolveConfig(tenantId, providerCode);
        if (config == null) return null;
        try {
            List<float[]> result = callEmbeddingApi(config, List.of(text));
            if (result.size() != 1) throw new IllegalStateException("Expected one embedding result");
            float[] vector = result.get(0);
            if (config.dimensions > 0 && vector.length != config.dimensions) {
                throw new IllegalStateException("Embedding response dimension differs from request");
            }
            return new EmbeddedVector(vector, spaceIdentity(config, vector.length));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failure) {
            // Provider bodies may contain confidential input; report the type, never the body/key.
            log.warn("Versioned embedding request failed: {}", failure.getClass().getSimpleName());
            return null;
        }
    }

    private String spaceIdentity(EmbeddingConfig config, int actualDimensions) {
        if (actualDimensions <= 0) throw new IllegalArgumentException("Positive dimensions required");
        String endpoint = embeddingUrl(config);
        StringBuilder source = new StringBuilder();
        for (String part : List.of("embedding-space-v1", config.providerCode, endpoint, config.model,
                Integer.toString(config.dimensions), Integer.toString(actualDimensions), config.spaceVersion)) {
            source.append(part.length()).append(':').append(part);
        }
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /**
     * Embed a batch of texts (max 20 per API call). Splits into sub-batches if needed.
     *
     * @return list of float arrays in same order as input
     */
    public List<float[]> embedBatch(Long tenantId, List<String> texts, String providerCode) {
        if (texts == null || texts.isEmpty()) return List.of();

        EmbeddingConfig config = resolveConfig(tenantId, providerCode);
        if (config == null) {
            log.error("No EMBEDDING provider configured for code={}", providerCode);
            return List.of();
        }

        List<float[]> allResults = new ArrayList<>(texts.size());
        int maxBatch = config.maxBatchSize > 0 ? config.maxBatchSize : 20;

        for (int i = 0; i < texts.size(); i += maxBatch) {
            List<String> batch = texts.subList(i, Math.min(i + maxBatch, texts.size()));
            try {
                List<float[]> batchResults = callEmbeddingApi(config, batch);
                allResults.addAll(batchResults);

                // Rate limiting between batches
                if (i + maxBatch < texts.size()) {
                    Thread.sleep(BATCH_SLEEP_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Embedding batch interrupted at index {}", i);
                break;
            } catch (Exception e) {
                log.error("Embedding batch failed at index {}: {}", i, e.getMessage());
                // Fill with nulls for failed batch
                for (int j = 0; j < batch.size(); j++) {
                    allResults.add(null);
                }
            }
        }
        return allResults;
    }

    /**
     * Build the OpenAI-compatible /v1/embeddings request body.
     *
     * <p>Package-private + static so unit tests can verify body shape without
     * spinning up an HTTP server. The {@code dimensions} field is omitted when
     * {@code dimensions == 0} for backwards compatibility with providers that
     * don't support the parameter (older OpenAI, MiniMax embo-01 native, etc).
     *
     * <p>When {@code dimensions > 0}, providers that support Matryoshka
     * Representation Learning (Qwen text-embedding-v4, OpenAI
     * text-embedding-3-small/large) return a vector of the requested length.
     */
    static Map<String, Object> buildRequestBody(String model, List<String> texts, int dimensions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);
        if (dimensions > 0) {
            body.put("dimensions", dimensions);
        }
        return body;
    }

    private String embeddingUrl(EmbeddingConfig config) {
        return config.baseUrl.endsWith("/") ? config.baseUrl + "v1/embeddings" : config.baseUrl + "/v1/embeddings";
    }

    @SuppressWarnings("unchecked")
    private List<float[]> callEmbeddingApi(EmbeddingConfig config, List<String> texts) throws Exception {
        Map<String, Object> body = buildRequestBody(config.model, texts, config.dimensions);
        String jsonBody = objectMapper.writeValueAsString(body);

        String url = embeddingUrl(config);

        // SSRF guard: baseUrl is tenant-configurable (CloudConfig). Reject private/
        // loopback/link-local targets and disallowed schemes, and pin the resolved IP
        // at connect time to close the DNS-rebinding window (SEC-20260723-05).
        SsrfValidator.ValidatedTarget target = SsrfValidator.validate(url);
        HttpRequest.Builder requestBuilder = target != null
                ? PinnedHttpRequests.newPinnedRequestBuilder(target)
                : HttpRequest.newBuilder().uri(URI.create(url));
        HttpRequest request = requestBuilder
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Embedding API returned " + response.statusCode() + ": " + response.body());
        }

        // Parse response: { "data": [{ "embedding": [...], "index": 0 }, ...] }
        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("Embedding API returned empty data");
        }

        // Sort by index and extract embeddings
        data.sort(Comparator.comparingInt(d -> ((Number) d.get("index")).intValue()));

        List<float[]> embeddings = new ArrayList<>(data.size());
        for (Map<String, Object> item : data) {
            List<Number> emb = (List<Number>) item.get("embedding");
            float[] arr = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                arr[i] = emb.get(i).floatValue();
            }
            embeddings.add(arr);
        }
        return embeddings;
    }

    @SuppressWarnings("unchecked")
    EmbeddingConfig resolveConfig(Long tenantId, String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            // F3 (execution-architecture review, 2026-07-20): 'openai' was
            // hardcoded here while the seeder provisions whatever vendor key the
            // deployment actually has (e.g. qianwen) — semantic recall silently
            // dead on every non-openai deployment ("No EMBEDDING provider
            // configured for code=openai"). Auto-resolve the first enabled
            // embedding provider, same posture as the chat LLM resolution;
            // 'openai' stays as the legacy last resort.
            java.util.List<com.auraboot.framework.cloudconfig.entity.CloudConfig> enabled =
                    cloudConfigService.getEnabledProviders(tenantId, "embedding");
            if (enabled != null && !enabled.isEmpty()
                    && enabled.get(0).getProviderCode() != null
                    && !enabled.get(0).getProviderCode().isBlank()) {
                providerCode = enabled.get(0).getProviderCode();
                log.debug("Auto-resolved EMBEDDING provider: {}", providerCode);
            } else {
                log.error("No enabled embedding provider is configured for tenant {}", tenantId);
                return null;
            }
        }

        try {
            CloudConfig cc = cloudConfigService.getEffectiveConfig(tenantId, "embedding", providerCode);
            if (cc != null && cc.getConfig() != null && !cc.getConfig().isBlank()) {
                Map<String, Object> cfg = objectMapper.readValue(cc.getConfig(), Map.class);
                String apiKey = (String) cfg.get("apiKey");
                if (apiKey != null && !apiKey.isBlank()) {
                    return new EmbeddingConfig(
                            apiKey,
                            getStr(cfg, "baseUrl", "https://api.openai.com"),
                            getStr(cfg, "defaultModel", "text-embedding-3-small"),
                            getInt(cfg, "maxBatchSize", 20),
                            getInt(cfg, "dimensions", 0), providerCode, getStr(cfg, "spaceVersion", "")
                    );
                }
            }
        } catch (Exception e) {
            log.debug("CloudConfig lookup failed for EMBEDDING/{}: {}", providerCode, e.getMessage());
        }
        return null;
    }

    private String getStr(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        return (v instanceof String s && !s.isBlank()) ? s : fallback;
    }

    private int getInt(Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    /**
     * Resolved embedding provider config. Package-private so unit tests can
     * inspect it; production callers go through {@link #resolveConfig}.
     *
     * @param dimensions output vector dimensions to request; 0 = provider
     *                   default (omit field from request body). Supported by
     *                   providers with Matryoshka Representation Learning
     *                   (Qwen text-embedding-v4, OpenAI text-embedding-3-*).
     */
    record EmbeddingConfig(String apiKey, String baseUrl, String model, int maxBatchSize, int dimensions,
                           String providerCode, String spaceVersion) {
        EmbeddingConfig(String apiKey, String baseUrl, String model, int maxBatchSize, int dimensions) {
            this(apiKey, baseUrl, model, maxBatchSize, dimensions, "unspecified", "");
        }
        @Override public String toString() { return "EmbeddingConfig[credentials=REDACTED]"; }
    }
}
