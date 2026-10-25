package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.integration.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link AnthropicLlmProvider}'s opt-in 1h prompt cache
 * TTL (ACP B.3 advanced — "anthropic-version 1h cache 升级").
 *
 * <p>Anthropic ships ephemeral cache with a default 5-minute lifetime; the
 * 1h tier requires both:
 * <ul>
 *   <li>request header {@code anthropic-beta: extended-cache-ttl-2025-04-11}</li>
 *   <li>{@code cache_control.ttl="1h"} on every emitted marker</li>
 * </ul>
 *
 * <p>Both are gated by the {@code agent.anthropic.cache.long-ttl} flag — the
 * field is package-private and we flip it via {@link ReflectionTestUtils}
 * inside each test, restoring the original value in {@link AfterEach} so
 * test ordering does not leak.
 *
 * <p>Cases:
 * <ol>
 *   <li>long-ttl=true → header present + cache_control.ttl="1h"</li>
 *   <li>long-ttl=false (default) → no beta header + no ttl field</li>
 *   <li>1h cache hit → counter increments with ttl="1h" tag</li>
 *   <li>5m cache hit (default) → counter increments with ttl="5m" tag</li>
 * </ol>
 */
class AnthropicLlmProviderLongTtlCacheIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AnthropicLlmProvider provider;

    @Autowired
    private MeterRegistry meterRegistry;

    private Boolean originalLongTtl;
    private WebClient originalWebClient;

    @AfterEach
    void restoreFlags() {
        if (originalLongTtl != null) {
            ReflectionTestUtils.setField(provider, "cacheLongTtl", originalLongTtl);
            originalLongTtl = null;
        }
        if (originalWebClient != null) {
            ReflectionTestUtils.setField(provider, "webClient", originalWebClient);
            originalWebClient = null;
        }
    }

    private void setLongTtl(boolean enabled) {
        if (originalLongTtl == null) {
            originalLongTtl = (Boolean) ReflectionTestUtils.getField(provider, "cacheLongTtl");
        }
        ReflectionTestUtils.setField(provider, "cacheLongTtl", enabled);
    }

    /**
     * Build a WebClient whose ExchangeFunction snapshots the outbound request
     * (so the test can assert headers) and returns the canned response.
     */
    private WebClient capturingClient(String responseJson, AtomicReference<HttpHeaders> capturedHeaders) {
        ExchangeFunction fn = request -> {
            capturedHeaders.set(request.headers());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(responseJson)
                    .build());
        };
        return WebClient.builder().exchangeFunction(fn).build();
    }

    private void installCapturingClient(String responseJson, AtomicReference<HttpHeaders> capturedHeaders) {
        if (originalWebClient == null) {
            originalWebClient = (WebClient) ReflectionTestUtils.getField(provider, "webClient");
        }
        ReflectionTestUtils.setField(provider, "webClient", capturingClient(responseJson, capturedHeaders));
    }

    private LlmChatRequest sampleRequest(String model) {
        return LlmChatRequest.builder()
                .model(model)
                .maxTokens(256)
                .systemPrompt("You are an assistant.")
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("hi").build()))
                .build();
    }

    private double counterValue(String name, String model, String ttl) {
        Search s = meterRegistry.find(name)
                .tag("provider", "anthropic")
                .tag("model", model)
                .tag("ttl", ttl);
        return s.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstSystemBlock(LlmChatRequest req) throws Exception {
        // Drive the converter directly so we don't need to round-trip through
        // the wire — assertions on cache_control.ttl are clearer this way.
        Method m = AnthropicLlmProvider.class.getDeclaredMethod(
                "buildAnthropicRequest", LlmChatRequest.class);
        m.setAccessible(true);
        Object anthropicReq = m.invoke(provider, req);
        Method getSystem = anthropicReq.getClass().getDeclaredMethod("getSystem");
        getSystem.setAccessible(true);
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) getSystem.invoke(anthropicReq);
        assertThat(blocks).isNotNull().isNotEmpty();
        return blocks.get(0);
    }

    @Test
    void longTtlEnabledAttachesBetaHeaderAndTtlField() throws Exception {
        // Case A — flip the flag, drive a chat() call, verify both knobs land.
        setLongTtl(true);
        AtomicReference<HttpHeaders> captured = new AtomicReference<>();
        installCapturingClient(
                "{\"id\":\"msg_a\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"end_turn\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,"
                        + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}",
                captured);

        LlmChatRequest req = sampleRequest("claude-sonnet-4-6");
        provider.chat(req, "test-key", "https://api.anthropic.com");

        // Header check — anthropic-beta MUST include the extended-cache-ttl
        // beta token. Anthropic concatenates multiple beta features with
        // commas, so we use containsString rather than isEqualTo.
        assertThat(captured.get())
                .as("captured headers must be populated")
                .isNotNull();
        List<String> beta = captured.get().get("anthropic-beta");
        assertThat(beta)
                .as("anthropic-beta header MUST be present when long-ttl is on")
                .isNotNull()
                .anyMatch(v -> v.contains("extended-cache-ttl-2025-04-11"));

        // cache_control payload check — every marker must carry ttl=1h.
        Map<String, Object> systemBlock = firstSystemBlock(req);
        assertThat(systemBlock).containsKey("cache_control");
        @SuppressWarnings("unchecked")
        Map<String, Object> cc = (Map<String, Object>) systemBlock.get("cache_control");
        assertThat(cc)
                .as("1h cache_control MUST carry ttl=1h alongside type=ephemeral")
                .containsEntry("type", "ephemeral")
                .containsEntry("ttl", "1h");
    }

    @Test
    void longTtlDisabledOmitsBetaHeaderAndTtlField() throws Exception {
        // Case B — default off path: no header, no ttl field. This is the
        // single most important regression assertion: turning on the long-ttl
        // feature must not retroactively bill existing tenants for 1h cache
        // because of a default flip.
        setLongTtl(false);
        AtomicReference<HttpHeaders> captured = new AtomicReference<>();
        installCapturingClient(
                "{\"id\":\"msg_b\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"end_turn\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,"
                        + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}",
                captured);

        LlmChatRequest req = sampleRequest("claude-sonnet-4-6");
        provider.chat(req, "test-key", "https://api.anthropic.com");

        assertThat(captured.get()).isNotNull();
        List<String> beta = captured.get().get("anthropic-beta");
        // The header value list is null when no header was set — Spring's
        // HttpHeaders returns null (not an empty list) for missing keys.
        assertThat(beta)
                .as("default-off path MUST NOT send the extended-cache-ttl beta header")
                .isNull();

        Map<String, Object> systemBlock = firstSystemBlock(req);
        @SuppressWarnings("unchecked")
        Map<String, Object> cc = (Map<String, Object>) systemBlock.get("cache_control");
        assertThat(cc)
                .as("default-off path MUST NOT carry a ttl field")
                .containsEntry("type", "ephemeral")
                .doesNotContainKey("ttl");
    }

    @Test
    void cacheHitWithLongTtlIncrementsCounterTaggedTtl1h() throws Exception {
        // Case C — when long-ttl is on and Anthropic reports a cache read,
        // the hit counter must carry ttl="1h" so dashboards can split.
        setLongTtl(true);
        AtomicReference<HttpHeaders> captured = new AtomicReference<>();
        installCapturingClient(
                "{\"id\":\"msg_c\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"end_turn\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                        + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":2048}}",
                captured);

        // Use a model code distinct from other tests so the counter tally
        // for this case is isolated.
        String model = "claude-opus-4-7";
        double hit1hBefore = counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model, "1h");
        double hit5mBefore = counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model, "5m");

        provider.chat(sampleRequest(model), "test-key", "https://api.anthropic.com");

        assertThat(counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model, "1h") - hit1hBefore)
                .as("1h hit counter must increment when long-ttl is on")
                .isEqualTo(1.0);
        assertThat(counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model, "5m") - hit5mBefore)
                .as("5m hit counter MUST NOT move on a 1h-tagged call")
                .isEqualTo(0.0);
    }

    @Test
    void cacheHitWithDefaultTtlIncrementsCounterTaggedTtl5m() throws Exception {
        // Case D — back-compat: with long-ttl off (default), the counter
        // gains ttl="5m". This verifies the new tag dimension on the
        // existing cache-hit path without breaking observability dashboards
        // built before the ttl tag existed.
        setLongTtl(false);
        AtomicReference<HttpHeaders> captured = new AtomicReference<>();
        installCapturingClient(
                "{\"id\":\"msg_d\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"end_turn\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                        + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":2048}}",
                captured);

        String model = "claude-haiku-4";
        double hit5mBefore = counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model, "5m");
        double hit1hBefore = counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model, "1h");

        provider.chat(sampleRequest(model), "test-key", "https://api.anthropic.com");

        assertThat(counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model, "5m") - hit5mBefore)
                .as("5m hit counter must increment by default")
                .isEqualTo(1.0);
        assertThat(counterValue(AnthropicLlmProvider.CACHE_HIT_NAME, model, "1h") - hit1hBefore)
                .as("1h hit counter MUST NOT move on a 5m-tagged call")
                .isEqualTo(0.0);
    }
}
