package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.SseResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-2 Blocker B2 — proof that provider-level Extended Thinking streams reach
 * the ResponseSink as a single {@code onThinking} call per aggregate block.
 *
 * <p>Spring is intentionally NOT bootstrapped here: the test wires the service
 * by hand and uses a mocked provider stream so the AuraBot adapter contract
 * stays independent from provider-specific HTTP details.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code thinking_delta} chunks are accumulated per block index and
 *       emitted as a single {@code onThinking(content, tokens, signature)}
 *       call on {@code content_block_stop}.</li>
 *   <li>{@code signature_delta} chunks land in the signature parameter.</li>
 *   <li>The trailing {@code text} block still streams via {@code onTextChunk}
 *       (i.e. thinking is additive — it does not replace the answer).</li>
 *   <li>{@link SseResponseSink} forwards {@code onThinking} as an SSE event
 *       named {@code "thinking"} with {@code {content, tokens, signature}}
 *       payload — this is the wire contract the frontend
 *       {@code processSSEStream} switch-case matches on.</li>
 * </ul>
 */
class AuraBotChatServiceThinkingIntegrationTest {

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    // =========================================================================
    // B2: provider thinking blocks reach ResponseSink.onThinking
    // =========================================================================

    @Test
    @DisplayName("provider thinking blocks emit one onThinking event per aggregate block")
    void providerThinkingBlocksEmitOneOnThinkingPerAggregateBlock() {
        MetaContext.setContext(1L, 100L, null, "tester");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        ChatToolResolver chatToolResolver = mock(ChatToolResolver.class);
        LlmProvider provider = mock(LlmProvider.class);
        AuraBotChatService service = new AuraBotChatService(
                factory,
                promptTemplateService,
                chatToolResolver,
                mock(ChatToolExecutor.class),
                new ObjectMapper(),
                mock(AiTraceService.class),
                mock(MetaModelService.class),
                new ChatTurnRuntime(),
                (Executor) Runnable::run);
        ChatRequest request = new ChatRequest();
        request.setSessionId("session-1");
        request.setMessage("hello");
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setProvider("anthropic");
        request.setOptions(options);
        when(factory.resolveConfig(eq(1L), eq("anthropic")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("anthropic")
                        .apiKey("test-key")
                        .baseUrl("stub://local")
                        .defaultModel("claude-sonnet-4-6")
                        .maxTokens(4096)
                        .build());
        when(chatToolResolver.resolveTools(eq("hello"), eq(null), eq(null), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(List.of(), null, null, true));
        when(factory.getProvider(eq("anthropic"))).thenReturn(provider);
        when(provider.streamChat(any(), eq("test-key"), eq("stub://local")))
                .thenReturn(Flux.just(
                        LlmChunk.thinking(0, "Let me think. "),
                        LlmChunk.thinking(1, "The answer is 42."),
                        LlmChunk.delta(2, "42"),
                        LlmChunk.done(3, LlmChatResponse.builder()
                                .stopReason("end_turn")
                                .content(List.of(
                                        LlmChatResponse.ContentBlock.builder()
                                                .type("thinking")
                                                .thinking("Let me think. The answer is 42.")
                                                .signature("sigABC")
                                                .build(),
                                        LlmChatResponse.ContentBlock.builder()
                                                .type("text")
                                                .text("42")
                                                .build()))
                                .build())));
        CapturingSink sink = new CapturingSink();

        TurnOutcome outcome = service.executeAuraBotTurn(
                TurnContext.legacyDefault(1L, 100L, 100L),
                request,
                sink);

        // Exactly one thinking event — the two thinking_delta chunks were
        // accumulated by the provider and the signature piggy-backed onto the same block.
        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        assertThat(sink.thinkings)
                .as("each {type:thinking} content block must produce one onThinking call, "
                        + "not one per delta")
                .hasSize(1);
        ThinkingEvent thinking = sink.thinkings.get(0);
        assertThat(thinking.content)
                .as("thinking_delta chunks must be concatenated in stream order")
                .isEqualTo("Let me think. The answer is 42.");
        assertThat(thinking.signature).isEqualTo("sigABC");
        assertThat(thinking.tokens).isEqualTo(-1); // best-effort sentinel; per-block usage absent

        // The text block downstream of the thinking block still streams as chunks.
        assertThat(sink.textChunks).contains("42");
        assertThat(sink.done).isTrue();
    }

    // =========================================================================
    // SSE wire format: SseResponseSink renders onThinking as a "thinking" event
    // with {content, tokens, signature} payload — this is the contract the
    // frontend processSSEStream switch-case matches.
    // =========================================================================

    @Test
    @DisplayName("sseResponseSink_onThinking_emitsThinkingEventWithJsonPayload")
    void sseResponseSink_onThinking_emitsThinkingEventWithJsonPayload() throws Exception {
        // Stand up a CapturingSseEmitter, route onThinking through SseResponseSink,
        // and verify the wire bytes are name=thinking with a JSON-string payload
        // that contains content + tokens + signature.
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseResponseSink sink = new SseResponseSink(emitter, new ObjectMapper());

        sink.onThinking("Step 1. Step 2.", 137, "sigXYZ");

        // The SSE event name must be "thinking" — the frontend's
        // processSSEStream switch-case matches on currentEvent === 'thinking'.
        assertThat(emitter.events)
                .anySatisfy(event -> {
                    assertThat(event.name).isEqualTo("thinking");
                    assertThat(event.payload).contains("\"content\":\"Step 1. Step 2.\"");
                    assertThat(event.payload).contains("\"tokens\":137");
                    assertThat(event.payload).contains("\"signature\":\"sigXYZ\"");
                });
    }

    @Test
    @DisplayName("sseResponseSink_onThinking_omitsSignatureWhenNull")
    void sseResponseSink_onThinking_omitsSignatureWhenNull() throws Exception {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseResponseSink sink = new SseResponseSink(emitter, new ObjectMapper());

        sink.onThinking("Some prose", -1, null);

        assertThat(emitter.events)
                .anySatisfy(event -> {
                    assertThat(event.name).isEqualTo("thinking");
                    assertThat(event.payload)
                            .as("signature must be omitted when null so the wire stays compact")
                            .doesNotContain("signature");
                    assertThat(event.payload).contains("\"tokens\":-1");
                });
    }

    // =========================================================================
    // Test fixtures
    // =========================================================================

    private static class CapturingSink implements ResponseSink {
        final List<String> textChunks = new ArrayList<>();
        final List<ThinkingEvent> thinkings = new ArrayList<>();
        boolean done = false;
        String error;

        @Override
        public void onTextChunk(String text) {
            textChunks.add(text);
        }

        @Override
        public void onThinking(String content, int tokens, String signature) {
            thinkings.add(new ThinkingEvent(content, tokens, signature));
        }

        @Override
        public void onToolStart(String toolId, String toolName, Map<String, Object> input) {}

        @Override
        public void onToolResult(String toolId, Map<String, Object> result, boolean success) {}

        @Override
        public void onConfirmRequired(String toolId, String toolName, String description,
                                       Map<String, Object> input, String pendingTurnId) {}

        @Override
        public void onError(String message, String traceId) {
            this.error = message;
        }

        @Override
        public void onDone(String finalResponse, String traceId) {
            this.done = true;
        }

        @Override
        public void onResultContract(ResultContract contract) {}
    }

    private record ThinkingEvent(String content, int tokens, String signature) {}

    private static class CapturingSseEmitter extends SseEmitter {
        final List<EmittedEvent> events = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            String name = "message";
            StringBuilder payload = new StringBuilder();
            for (var entry : builder.build()) {
                Object data = entry.getData();
                if (data instanceof String text) {
                    int eventIndex = text.indexOf("event:");
                    if (eventIndex >= 0) {
                        String tail = text.substring(eventIndex + 6);
                        int newline = tail.indexOf('\n');
                        name = (newline >= 0 ? tail.substring(0, newline) : tail).trim();
                    } else if (!text.startsWith("data:") && !text.isBlank()
                            && !"\n".equals(text) && !"\n\n".equals(text) && !":".equals(text)) {
                        payload.append(text);
                    }
                } else if (data != null) {
                    payload.append(data);
                }
            }
            // The SseResponseSink uses sendJsonString → builder.data(jsonString),
            // which the framework would normally serialise as `data: <json>\n`.
            // Our capture just grabs the body so we can string-match.
            events.add(new EmittedEvent(name, payload.toString()));
        }
    }

    private record EmittedEvent(String name, String payload) {}

    @SuppressWarnings("unused")
    private static Map<String, Object> linkedMap(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
