package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.AnthropicRequest;
import com.auraboot.framework.agent.dto.AnthropicResponse;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for AnthropicLlmProvider — focuses on Anthropic ephemeral
 * prompt caching: cache_control marker placement, response usage mapping
 * for cache_creation/cache_read tokens, and cache-aware cost estimation.
 */
class AnthropicLlmProviderTest {

    private AnthropicLlmProvider createProvider() {
        // WebClient is not exercised by the methods under test; pass null.
        return new AnthropicLlmProvider(null, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private List<AnthropicRequest.Tool> convertTools(AnthropicLlmProvider provider, List<LlmChatRequest.Tool> tools)
            throws Exception {
        Method m = AnthropicLlmProvider.class.getDeclaredMethod("convertTools", List.class);
        m.setAccessible(true);
        return (List<AnthropicRequest.Tool>) m.invoke(provider, tools);
    }

    private Object convertSystem(AnthropicLlmProvider provider, String systemPrompt) throws Exception {
        Method m = AnthropicLlmProvider.class.getDeclaredMethod("convertSystem", String.class);
        m.setAccessible(true);
        return m.invoke(provider, systemPrompt);
    }

    private LlmChatResponse convertResponse(AnthropicLlmProvider provider, AnthropicResponse resp) throws Exception {
        Method m = AnthropicLlmProvider.class.getDeclaredMethod("convertResponse", AnthropicResponse.class);
        m.setAccessible(true);
        return (LlmChatResponse) m.invoke(provider, resp);
    }

    // =========================================================================
    // (a) cache_control appears on the LAST tool only
    // =========================================================================

    @Test
    void convertToolsMarksOnlyLastToolWithEphemeralCacheControl() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        List<LlmChatRequest.Tool> input = List.of(
                LlmChatRequest.Tool.builder().name("first").description("d1")
                        .inputSchema(Map.of("type", "object")).build(),
                LlmChatRequest.Tool.builder().name("middle").description("d2")
                        .inputSchema(Map.of("type", "object")).build(),
                LlmChatRequest.Tool.builder().name("last").description("d3")
                        .inputSchema(Map.of("type", "object")).build()
        );

        List<AnthropicRequest.Tool> result = convertTools(provider, input);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getCache_control()).isNull();
        assertThat(result.get(1).getCache_control()).isNull();
        assertThat(result.get(2).getCache_control())
                .as("last tool must carry ephemeral cache_control marker")
                .isNotNull()
                .containsEntry("type", "ephemeral");
    }

    @Test
    void convertToolsHandlesEmptyAndNull() throws Exception {
        AnthropicLlmProvider provider = createProvider();
        assertThat(convertTools(provider, null)).isNull();
        assertThat(convertTools(provider, List.of())).isNull();
    }

    @Test
    void convertToolsSerializesCacheControlInJsonOutput() throws Exception {
        AnthropicLlmProvider provider = createProvider();
        ObjectMapper mapper = new ObjectMapper();

        List<AnthropicRequest.Tool> tools = convertTools(provider, List.of(
                LlmChatRequest.Tool.builder().name("only").description("d")
                        .inputSchema(Map.of("type", "object")).build()
        ));

        AnthropicRequest req = AnthropicRequest.builder()
                .model("claude-sonnet-4-6")
                .max_tokens(1024)
                .system("You are helpful.")
                .messages(List.of())
                .tools(tools)
                .build();

        String json = mapper.writeValueAsString(req);
        assertThat(json).contains("\"cache_control\":{\"type\":\"ephemeral\"}");
    }

    // =========================================================================
    // System prompt: wrapped as block list with ephemeral cache_control
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void convertSystemWrapsStringAsBlockListWithCacheControl() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        Object result = convertSystem(provider, "You are an enterprise assistant.");

        assertThat(result).isInstanceOf(List.class);
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) result;
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0))
                .containsEntry("type", "text")
                .containsEntry("text", "You are an enterprise assistant.")
                .containsKey("cache_control");
        Map<String, Object> cc = (Map<String, Object>) blocks.get(0).get("cache_control");
        assertThat(cc).containsEntry("type", "ephemeral");
    }

    @Test
    void convertSystemReturnsNullForBlankPrompt() throws Exception {
        AnthropicLlmProvider provider = createProvider();
        assertThat(convertSystem(provider, null)).isNull();
        assertThat(convertSystem(provider, "")).isNull();
        assertThat(convertSystem(provider, "   ")).isNull();
    }

    // =========================================================================
    // (b) Response usage maps cache_creation_input_tokens and cache_read_input_tokens
    // =========================================================================

    @Test
    void convertResponseMapsCacheTokensIntoUnifiedUsage() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        AnthropicResponse resp = new AnthropicResponse();
        AnthropicResponse.Usage usage = new AnthropicResponse.Usage();
        usage.setInput_tokens(100);
        usage.setOutput_tokens(50);
        usage.setCache_creation_input_tokens(2000);
        usage.setCache_read_input_tokens(8000);
        resp.setUsage(usage);
        resp.setStop_reason("end_turn");
        resp.setContent(List.of());

        LlmChatResponse out = convertResponse(provider, resp);

        assertThat(out.getInputTokens()).isEqualTo(100);
        assertThat(out.getOutputTokens()).isEqualTo(50);
        assertThat(out.getCacheCreationInputTokens()).isEqualTo(2000);
        assertThat(out.getCacheReadInputTokens()).isEqualTo(8000);
        assertThat(out.getStopReason()).isEqualTo("end_turn");
    }

    @Test
    void convertResponseDefaultsCacheTokensToZeroWhenAbsent() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        AnthropicResponse resp = new AnthropicResponse();
        AnthropicResponse.Usage usage = new AnthropicResponse.Usage();
        usage.setInput_tokens(123);
        usage.setOutput_tokens(45);
        // cache fields left as primitive 0
        resp.setUsage(usage);
        resp.setContent(List.of());

        LlmChatResponse out = convertResponse(provider, resp);

        assertThat(out.getCacheCreationInputTokens()).isEqualTo(0);
        assertThat(out.getCacheReadInputTokens()).isEqualTo(0);
    }

    // =========================================================================
    // (c) estimateCost honours cache write 1.25x and cache read 0.1x
    // =========================================================================

    @Test
    void estimateCostThreeArgUnchangedForBackwardCompatibility() {
        AnthropicLlmProvider provider = createProvider();

        // Sonnet: input=3.0, output=15.0 per 1M
        // (1000 * 3.0 + 500 * 15.0) / 1_000_000 = 10500 / 1_000_000 = 0.0105
        double cost = provider.estimateCost("claude-sonnet-4-6", 1000, 500);
        assertThat(cost).isEqualTo(0.0105);
    }

    @Test
    void estimateCostFiveArgChargesCacheCreateAt1_25xAndCacheReadAt0_1x() {
        AnthropicLlmProvider provider = createProvider();

        // Sonnet base input 3.0 / output 15.0 per 1M
        // input(non-cached)     = 1000 tokens @ 3.0           = 3000
        // cache_creation_input  = 4000 tokens @ 3.0 * 1.25    = 15000
        // cache_read_input      = 10000 tokens @ 3.0 * 0.10   = 3000
        // output                = 500 tokens @ 15.0           = 7500
        // total                 = 28500 / 1_000_000           = 0.0285
        double cost = provider.estimateCost("claude-sonnet-4-6", 1000, 500, 4000, 10000);
        assertThat(cost).isCloseTo(0.0285, within(1e-9));
    }

    @Test
    void estimateCostFiveArgWithOnlyCacheReadHits() {
        AnthropicLlmProvider provider = createProvider();

        // pure cache hit: input=0, cacheCreate=0, cacheRead=10000 → 10000 * 3.0 * 0.1 = 3000 → 0.003
        double cost = provider.estimateCost("claude-sonnet-4-6", 0, 0, 0, 10000);
        assertThat(cost).isCloseTo(0.003, within(1e-9));

        // sanity: no-cache equivalent of 10000 input would cost 10000 * 3.0 = 30000 → 0.030
        double noCacheCost = provider.estimateCost("claude-sonnet-4-6", 10000, 0);
        assertThat(noCacheCost).isCloseTo(0.030, within(1e-9));

        // Cache hit is exactly 10x cheaper for the cached portion
        assertThat(cost).isCloseTo(noCacheCost / 10.0, within(1e-9));
    }

    @Test
    void estimateCostFiveArgUsesOpusRatesWhenModelContainsOpus() {
        AnthropicLlmProvider provider = createProvider();

        // Opus base input 15.0 per 1M
        // input(non-cached)    = 1000 * 15.0          = 15000
        // cache_creation_input = 1000 * 15.0 * 1.25   = 18750
        // cache_read_input     = 1000 * 15.0 * 0.10   = 1500
        // output               = 0
        // total = 35250 / 1_000_000 = 0.03525
        double cost = provider.estimateCost("claude-opus-4-7", 1000, 0, 1000, 1000);
        assertThat(cost).isCloseTo(0.03525, within(1e-9));
    }

    @Test
    void estimateCostFiveArgUsesHaikuRatesWhenModelContainsHaiku() {
        AnthropicLlmProvider provider = createProvider();

        // Haiku base input 0.25 per 1M / output 1.25 per 1M
        // input(non-cached)    = 10000 * 0.25         = 2500
        // cache_creation_input = 4000 * 0.25 * 1.25   = 1250
        // cache_read_input     = 8000 * 0.25 * 0.10   = 200
        // output               = 1000 * 1.25          = 1250
        // total = 5200 / 1_000_000 = 0.0052
        double cost = provider.estimateCost("claude-haiku-3-5", 10000, 1000, 4000, 8000);
        assertThat(cost).isCloseTo(0.0052, within(1e-9));
    }

    // =========================================================================
    // Interface dispatch — StepLoopService / AiScoringService call sites
    // hold an LlmProvider reference, not the concrete class. This pins down
    // that the 5-arg cache-aware overload resolves to AnthropicLlmProvider's
    // override (1.25x write / 0.10x read) rather than the LlmProvider default
    // (which delegates to the 3-arg path and therefore prices cache hits
    // identical to fresh tokens).
    // =========================================================================

    @Test
    void fiveArgEstimateCostOnLlmProviderInterfaceDispatchesToAnthropicOverride() {
        LlmProvider provider = createProvider();   // upcast — same shape as StepLoopService

        // Pure cache hit: only cache_read tokens. Anthropic override must
        // bill at 0.10x base input rate; the LlmProvider default would
        // bill 0.0 (since 3-arg sees inputTokens=0, outputTokens=0).
        double cacheHitCost = provider.estimateCost("claude-sonnet-4-6", 0, 0, 0, 10000);
        // 10000 * 3.0 * 0.10 / 1_000_000 = 0.003
        assertThat(cacheHitCost)
                .as("cache hit on Anthropic must be billed (0.1x) — not free")
                .isCloseTo(0.003, within(1e-9));

        // The same 10000 tokens treated as fresh input cost exactly 10x more.
        double freshCost = provider.estimateCost("claude-sonnet-4-6", 10000, 0, 0, 0);
        assertThat(freshCost).isCloseTo(cacheHitCost * 10.0, within(1e-9));
    }
}
