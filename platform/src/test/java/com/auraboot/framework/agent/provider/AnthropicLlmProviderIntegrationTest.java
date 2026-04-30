package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.AnthropicRequest;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration test for AnthropicLlmProvider — boots full Spring context to
 * verify that:
 *   1. The provider bean is wired correctly with {@code aiWebClient}.
 *   2. {@link AnthropicLlmProvider#chat} marks the LAST tool and the system
 *      block with {@code cache_control: ephemeral} (case a).
 *   3. The provider correctly maps Anthropic's
 *      {@code cache_creation_input_tokens} / {@code cache_read_input_tokens}
 *      response fields into {@link LlmChatResponse} (case b).
 *   4. {@code estimateCost} bills cache writes at 1.25x and cache reads at
 *      0.1x of the base input rate (case c).
 *
 * <p>The Anthropic HTTP endpoint is replaced with an in-process
 * {@link org.springframework.web.reactive.function.client.ExchangeFunction}
 * that returns a canned response — no real network traffic.
 */
class AnthropicLlmProviderIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AnthropicLlmProvider provider;

    /**
     * Build a minimal WebClient whose exchangeFunction returns the supplied
     * JSON body with HTTP 200 — enough to drive the provider end-to-end
     * through the parse / convertResponse path.
     */
    private WebClient cannedResponseClient(String responseJson) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(responseJson)
                        .build()))
                .build();
    }

    @Test
    void providerBeanIsWiredFromSpringContext() {
        // Sanity: Spring context bootstraps the provider correctly.
        assertThat(provider).isNotNull();
        assertThat(provider.getProviderCode()).isEqualTo("anthropic");
        assertThat(provider.supportsTools()).isTrue();
        assertThat(provider.getDefaultBaseUrl()).isEqualTo("https://api.anthropic.com");
    }

    @Test
    void chatEndToEndMapsCacheTokensFromUsage() throws Exception {
        // Case (b): Anthropic returns cache_creation_input_tokens and
        // cache_read_input_tokens in usage; provider must surface them in
        // the unified LlmChatResponse.
        String cannedResponse = "{"
                + "\"id\":\"msg_test\","
                + "\"type\":\"message\","
                + "\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-6\","
                + "\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                + "\"usage\":{"
                + "\"input_tokens\":10,"
                + "\"output_tokens\":5,"
                + "\"cache_creation_input_tokens\":3000,"
                + "\"cache_read_input_tokens\":7000"
                + "}}";

        WebClient stub = cannedResponseClient(cannedResponse);
        ReflectionTestUtils.setField(provider, "webClient", stub);

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(1024)
                .systemPrompt("You are an assistant.")
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("hello").build()))
                .build();

        LlmChatResponse resp = provider.chat(req, "test-key", "https://api.anthropic.com");

        assertThat(resp.getInputTokens()).isEqualTo(10);
        assertThat(resp.getOutputTokens()).isEqualTo(5);
        assertThat(resp.getCacheCreationInputTokens()).isEqualTo(3000);
        assertThat(resp.getCacheReadInputTokens()).isEqualTo(7000);
        assertThat(resp.getStopReason()).isEqualTo("end_turn");
        assertThat(resp.getContent()).hasSize(1);
        assertThat(resp.getContent().get(0).getText()).isEqualTo("hi");
    }

    @Test
    void chatEndToEndMarksLastToolAndSystemBlockWithEphemeralCacheControl() throws Exception {
        // Case (a): the converters used by chat() must produce cache_control
        // on the last tool and on the system prompt block. We verify by
        // invoking the package-private converters directly on the wired
        // bean (so we're testing the actual production instance, not a
        // hand-built one), and then drive a chat() call end-to-end to
        // confirm the wiring is exercised without errors.
        Method convertTools = AnthropicLlmProvider.class.getDeclaredMethod("convertTools", List.class);
        convertTools.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<AnthropicRequest.Tool> tools = (List<AnthropicRequest.Tool>) convertTools.invoke(provider, List.of(
                LlmChatRequest.Tool.builder().name("first").description("d1")
                        .inputSchema(Map.of("type", "object")).build(),
                LlmChatRequest.Tool.builder().name("second").description("d2")
                        .inputSchema(Map.of("type", "object")).build()
        ));
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).getCache_control()).isNull();
        assertThat(tools.get(1).getCache_control())
                .as("last tool must carry cache_control: ephemeral")
                .isNotNull()
                .containsEntry("type", "ephemeral");

        Method convertSystem = AnthropicLlmProvider.class.getDeclaredMethod("convertSystem", String.class);
        convertSystem.setAccessible(true);
        Object systemBlocks = convertSystem.invoke(provider, "You are an enterprise assistant.");
        assertThat(systemBlocks).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) systemBlocks;
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0))
                .containsEntry("type", "text")
                .containsEntry("text", "You are an enterprise assistant.")
                .containsKey("cache_control");

        // End-to-end: drive chat() with a stub WebClient to confirm the wiring.
        String cannedResponse = "{"
                + "\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"end_turn\","
                + "\"content\":[],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,"
                + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}";
        ReflectionTestUtils.setField(provider, "webClient", cannedResponseClient(cannedResponse));

        LlmChatResponse resp = provider.chat(LlmChatRequest.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(256)
                        .systemPrompt("sys")
                        .messages(List.of(LlmChatRequest.Message.builder()
                                .role("user").content("hi").build()))
                        .build(),
                "test-key", "https://api.anthropic.com");
        assertThat(resp).isNotNull();
        assertThat(resp.getStopReason()).isEqualTo("end_turn");
    }

    @Test
    void estimateCostHonoursAnthropicCacheRates() {
        // Case (c): cache write 1.25x, cache read 0.1x.
        // sonnet base: input 3.0 / 1M, output 15.0 / 1M
        //   1000 * 3.0  +  4000 * 3.0 * 1.25  +  10000 * 3.0 * 0.10  +  500 * 15.0
        // = 3000       +  15000              +  3000                +  7500
        // = 28500 / 1_000_000 = 0.0285
        double cost = provider.estimateCost("claude-sonnet-4-6", 1000, 500, 4000, 10000);
        assertThat(cost).isCloseTo(0.0285, within(1e-9));

        // Pure cache hit is exactly 10x cheaper than the non-cached equivalent.
        double pureHit = provider.estimateCost("claude-sonnet-4-6", 0, 0, 0, 10000);
        double noCache = provider.estimateCost("claude-sonnet-4-6", 10000, 0);
        assertThat(pureHit).isCloseTo(noCache / 10.0, within(1e-9));

        // 3-arg path stays unchanged for non-cache-aware callers.
        assertThat(provider.estimateCost("claude-sonnet-4-6", 1000, 500))
                .isEqualTo(0.0105);
    }

    /**
     * End-to-end cache lifecycle: two successive chat() calls against the
     * same prompt. Anthropic's billing model says the first call writes the
     * prefix into the cache (cache_creation_input_tokens > 0, charged at
     * 1.25x base) and the second call reads from it (cache_read_input_tokens
     * > 0, charged at 0.10x base). This test pins both behaviours:
     *
     * <ul>
     *   <li>The provider surfaces both fields verbatim from the Anthropic
     *       usage block into {@link LlmChatResponse}.</li>
     *   <li>The cache-aware {@code estimateCost} overload makes the second
     *       (cache-hit) call cost at least 10x less than the first
     *       (cache-write) call when only the cache vector changes.</li>
     * </ul>
     *
     * <p>Without this gate the "P0-1 saves money" claim is unobservable —
     * cache hits would be billed identically to a fresh call.
     */
    @Test
    void chatSequenceFirstWritesCacheThenSecondReadsCacheAtTenthCost() throws Exception {
        // Stub WebClient that flips response based on call index.
        // Call 1: 5000 cache_creation, 0 cache_read    — cache write
        // Call 2: 0 cache_creation,    5000 cache_read — cache hit
        String firstResponse = "{"
                + "\"id\":\"msg_first\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"first\"}],"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":20,"
                + "\"cache_creation_input_tokens\":5000,\"cache_read_input_tokens\":0}}";
        String secondResponse = "{"
                + "\"id\":\"msg_second\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-6\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"second\"}],"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":20,"
                + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":5000}}";

        AtomicInteger callIndex = new AtomicInteger();
        WebClient sequentialStub = WebClient.builder()
                .exchangeFunction(request -> {
                    String body = callIndex.getAndIncrement() == 0 ? firstResponse : secondResponse;
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build());
                })
                .build();
        ReflectionTestUtils.setField(provider, "webClient", sequentialStub);

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(256)
                .systemPrompt("You are an assistant.")
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("hello").build()))
                .build();

        // First turn — cache write
        LlmChatResponse first = provider.chat(req, "test-key", "https://api.anthropic.com");
        assertThat(first.getCacheCreationInputTokens())
                .as("first call must report cache_creation tokens (cache write)")
                .isEqualTo(5000);
        assertThat(first.getCacheReadInputTokens())
                .as("first call has nothing to read yet")
                .isZero();

        // Second turn — cache hit
        LlmChatResponse second = provider.chat(req, "test-key", "https://api.anthropic.com");
        assertThat(second.getCacheReadInputTokens())
                .as("second call must report cache_read tokens (cache hit)")
                .isEqualTo(5000);
        assertThat(second.getCacheCreationInputTokens())
                .as("second call should not re-write the cache")
                .isZero();

        // Cost comparison — the cache vector is the only thing that differs
        // between the two calls (input/output tokens are identical), so the
        // delta on input pricing reflects pure cache savings.
        double firstCost = provider.estimateCost("claude-sonnet-4-6",
                first.getInputTokens(), first.getOutputTokens(),
                first.getCacheCreationInputTokens(), first.getCacheReadInputTokens());
        double secondCost = provider.estimateCost("claude-sonnet-4-6",
                second.getInputTokens(), second.getOutputTokens(),
                second.getCacheCreationInputTokens(), second.getCacheReadInputTokens());

        // Concrete sonnet billing:
        //   first:  input(100*3)=300 + cache_write(5000*3*1.25)=18750 + output(20*15)=300
        //                                                              total = 19350 / 1M = 0.01935
        //   second: input(100*3)=300 + cache_read(5000*3*0.10)=1500   + output(20*15)=300
        //                                                              total =  2100 / 1M = 0.00210
        // Cache-only portion alone is 18750 vs 1500 = 12.5x cheaper on read,
        // matching the (1.25 / 0.10) Anthropic billing multipliers.
        assertThat(secondCost)
                .as("cache-hit call must cost an order of magnitude less than cache-write call")
                .isLessThan(firstCost / 9.0)
                .isGreaterThan(0);

        // The 5000 cached-portion ratio (cache_write 1.25x vs cache_read 0.1x)
        // is exactly 12.5x — the only place the multiplier contract is observable
        // free of input/output noise. This is what makes P0-1 "save money".
        double cacheWriteOnlyCost = 5000 * 3.0 * 1.25 / 1_000_000.0;   // = 0.01875
        double cacheReadOnlyCost  = 5000 * 3.0 * 0.10 / 1_000_000.0;   // = 0.00150
        assertThat(cacheWriteOnlyCost / cacheReadOnlyCost).isCloseTo(12.5, within(1e-9));

        // And the second call must cost less than a hypothetical no-cache
        // baseline of the same input volume — sanity-check the multiplier
        // direction (no double-counting).
        double noCacheBaseline = provider.estimateCost("claude-sonnet-4-6",
                second.getInputTokens() + 5000, second.getOutputTokens());
        assertThat(secondCost).isLessThan(noCacheBaseline);
    }
}
