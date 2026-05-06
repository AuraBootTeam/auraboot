package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.integration.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link AnthropicLlmProvider}'s prompt cache hit/miss
 * Micrometer counters (ACP B.3-3).
 *
 * <p>Three cases drive the contract:
 * <ol>
 *   <li><b>Hit</b> — Anthropic response carries
 *       {@code usage.cache_read_input_tokens > 0} → only
 *       {@link AnthropicLlmProvider#CACHE_HIT_NAME} is incremented.</li>
 *   <li><b>Miss</b> — response carries
 *       {@code cache_creation_input_tokens > 0} and
 *       {@code cache_read_input_tokens == 0} → only
 *       {@link AnthropicLlmProvider#CACHE_MISS_NAME} is incremented.</li>
 *   <li><b>Neither</b> — no cache fields in the usage block → BOTH counters
 *       remain unchanged. This is critical: rolling uncached calls into either
 *       bucket would skew the operator-facing hit-rate ratio.</li>
 * </ol>
 *
 * <p>Counters are tagged {@code provider="anthropic"} and
 * {@code model=<requested model>} so operators can compute hit-rate per model
 * family in Grafana. Tag values come from the request, not the response, so a
 * fallback "claude-haiku-4" call still tags correctly even when Anthropic
 * echoes a different concrete model code.
 *
 * <p>The Anthropic HTTP endpoint is replaced with an in-process WebClient
 * stub returning a canned JSON body — no network traffic. Real PostgreSQL +
 * Redis are still required (BaseIntegrationTest) per the project's "no H2 /
 * no mock DB" red line, even though this test does not touch the DB.
 */
class AnthropicLlmProviderCacheMetricIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AnthropicLlmProvider provider;

    @Autowired
    private MeterRegistry meterRegistry;

    private WebClient cannedResponseClient(String responseJson) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(responseJson)
                        .build()))
                .build();
    }

    /**
     * Snapshot the running count for a (counter-name, provider, model) tuple.
     * Returns 0.0 when no counter has been registered yet so callers can
     * compute deltas without worrying about lazy-registration order.
     */
    private double counterValue(String name, String model) {
        Search s = meterRegistry.find(name).tag("provider", "anthropic").tag("model", model);
        return s.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private LlmChatRequest sampleRequest(String model) {
        return LlmChatRequest.builder()
                .model(model)
                .maxTokens(256)
                .systemPrompt("You are an assistant.")
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("hello").build()))
                .build();
    }

    @Test
    void chatRecordsCacheHitWhenResponseUsageHasCacheReadTokens() throws Exception {
        // Given: Anthropic returns usage with cache_read_input_tokens > 0
        String cannedResponse = "{"
                + "\"id\":\"msg_hit\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":512}}";
        ReflectionTestUtils.setField(provider, "webClient", cannedResponseClient(cannedResponse));

        String model = "claude-sonnet-4-6";
        double hitBefore = counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model);
        double missBefore = counterValue(AnthropicLlmProvider.CACHE_MISS_NAME, model);

        // When
        provider.chat(sampleRequest(model), "test-key", "https://api.anthropic.com");

        // Then: hit counter +1, miss counter unchanged
        assertThat(counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model) - hitBefore)
                .as("hit counter must increment when cache_read_input_tokens > 0")
                .isEqualTo(1.0);
        assertThat(counterValue(AnthropicLlmProvider.CACHE_MISS_NAME, model) - missBefore)
                .as("miss counter must not increment on a cache hit")
                .isEqualTo(0.0);
    }

    @Test
    void chatRecordsCacheMissWhenOnlyCreationTokensReported() throws Exception {
        // Given: cache_creation_input_tokens > 0 AND cache_read_input_tokens == 0
        // (fresh cache write with no read served on this call).
        String cannedResponse = "{"
                + "\"id\":\"msg_miss\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                + "\"cache_creation_input_tokens\":512,\"cache_read_input_tokens\":0}}";
        ReflectionTestUtils.setField(provider, "webClient", cannedResponseClient(cannedResponse));

        // Use a distinct model code so the test is not coupled to other tests'
        // counter state — the (provider, model) tag combination keeps tallies
        // isolated across cases.
        String model = "claude-opus-4-7";
        double hitBefore = counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model);
        double missBefore = counterValue(AnthropicLlmProvider.CACHE_MISS_NAME, model);

        // When
        provider.chat(sampleRequest(model), "test-key", "https://api.anthropic.com");

        // Then: miss counter +1, hit counter unchanged
        assertThat(counterValue(AnthropicLlmProvider.CACHE_MISS_NAME, model) - missBefore)
                .as("miss counter must increment when only cache_creation_input_tokens > 0")
                .isEqualTo(1.0);
        assertThat(counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model) - hitBefore)
                .as("hit counter must not increment on a cache miss")
                .isEqualTo(0.0);
    }

    @Test
    void chatLeavesBothCountersUnchangedWhenNoCacheFieldsReported() throws Exception {
        // Given: usage block has neither cache_creation_input_tokens nor
        // cache_read_input_tokens > 0 (uncached call). Note: the wire format
        // returns 0 for missing fields per Anthropic's int defaults — explicit
        // "neither" case, not a JSON parse fallback.
        String cannedResponse = "{"
                + "\"id\":\"msg_neither\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-haiku-4\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
        ReflectionTestUtils.setField(provider, "webClient", cannedResponseClient(cannedResponse));

        String model = "claude-haiku-4";
        double hitBefore = counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model);
        double missBefore = counterValue(AnthropicLlmProvider.CACHE_MISS_NAME, model);

        // When
        provider.chat(sampleRequest(model), "test-key", "https://api.anthropic.com");

        // Then: both counters unchanged — uncached calls must not pollute
        // either bucket. Operators compute hit-rate as
        //   hit / (hit + miss)
        // so an inflated denominator would silently wash out the signal.
        assertThat(counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model) - hitBefore)
                .as("hit counter must NOT increment when no cache tokens reported")
                .isEqualTo(0.0);
        assertThat(counterValue(AnthropicLlmProvider.CACHE_MISS_NAME, model) - missBefore)
                .as("miss counter must NOT increment when no cache tokens reported")
                .isEqualTo(0.0);
    }
}
