package com.auraboot.framework.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live check against DashScope, skipped when {@code DASHSCOPE_API_KEY} is absent.
 *
 * <p>This exists because of a failure mode that no offline test can catch: the vector column is
 * {@code vector(1536)}, and {@code text-embedding-v4} answers with <b>1024</b> dimensions unless the
 * request asks for 1536. A provider config that omits the dimension — or spells it {@code dimension}
 * instead of {@code dimensions}, which is what {@code EmbeddingService} actually reads — compiles,
 * passes every unit test, seeds cleanly, and then silently fails to embed a single chunk.
 *
 * <p>The request body is built by {@link EmbeddingService#buildRequestBody} — the same code the
 * service sends — so this asserts the real contract rather than a hand-written imitation of it.
 */
@DisplayName("DashScope embeddings (live)")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class EmbeddingDashScopeLiveIT {

    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
    private static final String MODEL = "text-embedding-v4";
    private static final int CHUNK_VECTOR_DIMENSIONS = 1536;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("returns vectors of exactly the width ab_kb_chunk.embedding expects")
    void returnsChunkColumnWidth() throws Exception {
        JsonNode response = embed(List.of(
                "退款政策:企业客户可在开票后 30 天内申请退款。",
                "Refunds are processed back to the original payment method within two weeks."));

        JsonNode data = response.path("data");
        assertThat(data).hasSize(2);

        for (JsonNode item : data) {
            assertThat(item.path("embedding").size())
                    .as("ab_kb_chunk.embedding is vector(%d) — anything else fails to insert",
                            CHUNK_VECTOR_DIMENSIONS)
                    .isEqualTo(CHUNK_VECTOR_DIMENSIONS);
        }
    }

    @Test
    @DisplayName("omitting the dimension really does yield 1024 — this is why the config pins it")
    void defaultDimensionIsNotTheColumnWidth() throws Exception {
        Map<String, Object> body = EmbeddingService.buildRequestBody(MODEL, List.of("x"), 0);
        assertThat(body).doesNotContainKey("dimensions");

        JsonNode response = post(body);
        int defaultWidth = response.path("data").get(0).path("embedding").size();

        assertThat(defaultWidth)
                .as("if this ever equals %d, the dimension pin is no longer load-bearing "
                        + "and this test should be revisited", CHUNK_VECTOR_DIMENSIONS)
                .isNotEqualTo(CHUNK_VECTOR_DIMENSIONS);
    }

    private JsonNode embed(List<String> texts) throws Exception {
        return post(EmbeddingService.buildRequestBody(MODEL, texts, CHUNK_VECTOR_DIMENSIONS));
    }

    private JsonNode post(Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + System.getenv("DASHSCOPE_API_KEY"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("DashScope rejected the request: %s", response.body())
                .isEqualTo(200);
        return objectMapper.readTree(response.body());
    }
}
