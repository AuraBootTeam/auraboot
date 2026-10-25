package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenAiCompatibleLlmProvider} real streaming (IMPL-02).
 *
 * <p>Before IMPL-02 this provider had no {@code streamChat} override and fell to
 * the {@link LlmProvider} default, which blocks on sync {@code chat} and emits a
 * single terminal chunk — pseudo-streaming for DeepSeek and every other
 * OpenAI-compatible model. These tests pin the real behaviour: (a) the
 * per-event parser {@code handleOpenAiSseData} maps {@code delta.content} to
 * incremental {@link LlmChunk} deltas, and (b) a canned {@code data:}-only SSE
 * stream replayed through a stub WebClient yields multiple in-progress deltas
 * plus one terminal aggregate — proving it is genuinely streaming, not one
 * blocking chunk. No network, no Spring context.
 */
class OpenAiCompatibleLlmProviderStreamingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OpenAiCompatibleLlmProvider providerWith(WebClient webClient) {
        return new OpenAiCompatibleLlmProvider(webClient, objectMapper);
    }

    /** Stub WebClient whose response body is a {@code data:}-only SSE stream (OpenAI wire shape). */
    private WebClient sseClient(String sseBody) {
        return WebClient.builder()
                .exchangeFunction(request -> reactor.core.publisher.Mono.just(
                        ClientResponse.create(HttpStatus.OK)
                                .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                                .body(sseBody)
                                .build()))
                .build();
    }

    private WebClient errorClient() {
        return WebClient.builder()
                .exchangeFunction(request -> reactor.core.publisher.Mono.just(
                        ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .body("{\"error\":{\"message\":\"rate limited\"}}")
                                .build()))
                .build();
    }

    private LlmChatRequest textReq() {
        return LlmChatRequest.builder()
                .model("deepseek-chat").maxTokens(64)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("hi").build()))
                .build();
    }

    // ---- unit: per-event parser -------------------------------------------------

    @Test
    void handleSseData_contentDelta_emitsDeltaChunkAndAccumulates() throws Exception {
        OpenAiCompatibleLlmProvider provider = providerWith(WebClient.builder().build());
        AtomicLong seq = new AtomicLong();
        var agg = new OpenAiCompatibleLlmProvider.OpenAiStreamAggregator();

        List<LlmChunk> chunks = provider.handleOpenAiSseData(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}", seq, agg)
                .collectList().block();

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).delta()).isEqualTo("Hello");
        assertThat(chunks.get(0).done()).isFalse();
        assertThat(agg.text.toString()).isEqualTo("Hello");
    }

    @Test
    void handleSseData_roleOnlyOpenerWithNullContent_emitsNoChunk() throws Exception {
        OpenAiCompatibleLlmProvider provider = providerWith(WebClient.builder().build());
        List<LlmChunk> chunks = provider.handleOpenAiSseData(
                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null}}]}",
                new AtomicLong(), new OpenAiCompatibleLlmProvider.OpenAiStreamAggregator())
                .collectList().block();
        assertThat(chunks).isEmpty();
    }

    @Test
    void handleSseData_reasoningContent_emitsThinkingDeltaAndDoesNotLeakIntoText() throws Exception {
        OpenAiCompatibleLlmProvider provider = providerWith(WebClient.builder().build());
        var agg = new OpenAiCompatibleLlmProvider.OpenAiStreamAggregator();
        List<LlmChunk> chunks = provider.handleOpenAiSseData(
                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"let me think\"}}]}",
                new AtomicLong(), agg).collectList().block();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).thinkingDelta()).isEqualTo("let me think");
        assertThat(chunks.get(0).delta()).isEmpty();
        assertThat(agg.text.toString()).isEmpty();
    }

    @Test
    void handleSseData_usageOnlyTrailingChunk_capturesTokensNoChunk() throws Exception {
        OpenAiCompatibleLlmProvider provider = providerWith(WebClient.builder().build());
        var agg = new OpenAiCompatibleLlmProvider.OpenAiStreamAggregator();
        List<LlmChunk> chunks = provider.handleOpenAiSseData(
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}",
                new AtomicLong(), agg).collectList().block();
        assertThat(chunks).isEmpty();
        assertThat(agg.promptTokens).isEqualTo(11);
        assertThat(agg.completionTokens).isEqualTo(3);
    }

    // ---- end-to-end streamChat over a stub SSE wire -----------------------------

    @Test
    void streamChat_multiChunkTextSequence_isRealStreamingAndAggregates() {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\", \"}}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"world!\"}}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}",
                "",
                "data: [DONE]",
                "");

        OpenAiCompatibleLlmProvider provider = providerWith(sseClient(sse));
        List<LlmChunk> chunks = provider.streamChat(textReq(), "sk-test", "https://api.deepseek.com")
                .collectList().block();

        assertThat(chunks).isNotNull();
        // 3 text deltas + 1 terminal = 4 (empty opener + finish + usage + [DONE] emit nothing)
        assertThat(chunks).hasSize(4);
        assertThat(chunks.subList(0, 3)).extracting(LlmChunk::done).containsOnly(false);
        assertThat(chunks.subList(0, 3)).extracting(LlmChunk::delta)
                .containsExactly("Hello", ", ", "world!");
        // seq is monotonic 0..3
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).seq()).isEqualTo((long) i);
        }

        LlmChunk terminal = chunks.get(3);
        assertThat(terminal.done()).isTrue();
        LlmChatResponse agg = terminal.aggregateResponse();
        assertThat(agg).isNotNull();
        assertThat(agg.getContent()).hasSize(1);
        assertThat(agg.getContent().get(0).getType()).isEqualTo("text");
        assertThat(agg.getContent().get(0).getText()).isEqualTo("Hello, world!");
        assertThat(agg.getStopReason()).isEqualTo("end_turn");
        assertThat(agg.getInputTokens()).isEqualTo(11);
        assertThat(agg.getOutputTokens()).isEqualTo(3);
    }

    @Test
    void streamChat_toolCallStreamedInFragments_aggregatesToToolUse() {
        String sse = String.join("\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"sales_lead_crm_create\",\"arguments\":\"\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"na\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"me\\\":\\\"Acme\\\"}\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "",
                "data: [DONE]",
                "");

        OpenAiCompatibleLlmProvider provider = providerWith(sseClient(sse));
        LlmChatRequest req = LlmChatRequest.builder().model("deepseek-chat").maxTokens(64)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("x").build()))
                .tools(List.of(LlmChatRequest.Tool.builder().name("sales_lead_crm:create")
                        .description("d").inputSchema(Map.of("type", "object")).build()))
                .build();

        List<LlmChunk> chunks = provider.streamChat(req, "sk-test", "https://api.deepseek.com")
                .collectList().block();

        // No text deltas → only the terminal chunk.
        assertThat(chunks).hasSize(1);
        LlmChatResponse agg = chunks.get(0).aggregateResponse();
        assertThat(agg.getStopReason()).isEqualTo("tool_use");
        assertThat(agg.getContent()).hasSize(1);
        LlmChatResponse.ContentBlock block = agg.getContent().get(0);
        assertThat(block.getType()).isEqualTo("tool_use");
        assertThat(block.getId()).isEqualTo("call_1");
        // Sanitized wire name mapped back to the original command code.
        assertThat(block.getName()).isEqualTo("sales_lead_crm:create");
        assertThat(block.getInput()).containsEntry("name", "Acme");
    }

    @Test
    void streamChat_httpError_surfacesAsFluxErrorNoFallbackToSync() {
        OpenAiCompatibleLlmProvider provider = providerWith(errorClient());
        StepVerifier.create(provider.streamChat(textReq(), "sk-test", "https://api.deepseek.com"))
                .expectError()
                .verify();
    }

    @Test
    void streamChat_visionModelGuard_failsFastBeforeWire() {
        OpenAiCompatibleLlmProvider provider = providerWith(WebClient.builder().build());
        LlmChatRequest req = LlmChatRequest.builder().model("deepseek-chat").maxTokens(64)
                .messages(List.of(LlmChatRequest.Message.builder().role("user")
                        .content(List.of(LlmChatRequest.MessageContentBlock.builder()
                                .type("image").build()))
                        .build()))
                .build();
        StepVerifier.create(provider.streamChat(req, "sk-test", "https://api.deepseek.com"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
