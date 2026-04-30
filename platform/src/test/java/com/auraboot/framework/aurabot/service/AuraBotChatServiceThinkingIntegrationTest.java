package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.SseResponseSink;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P0-2 Blocker B2 — full-stack proof that an Anthropic Extended Thinking
 * SSE stream (content_block_start/delta/stop with type=thinking) reaches the
 * ResponseSink as a single {@code onThinking} call per block.
 *
 * <p>Spring is intentionally NOT bootstrapped here: the streaming code uses a
 * raw {@code HttpClient} (not WebClient through DI) and does not depend on
 * any Spring bean — the test wires the service by hand and stands up an
 * in-process {@link HttpServer} that mimics Anthropic's SSE shape. This is
 * the same pattern as
 * {@link AuraBotChatServiceTracePayloadTest#openAiCompatibleStreamHidesSplitThinkBlocksFromVisibleSseEvents()}.
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

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /**
     * Anthropic SSE protocol fragment exercising:
     *   block 0 — thinking (two thinking_delta + one signature_delta + stop)
     *   block 1 — text     (one text_delta + stop)
     *   message_stop
     *
     * <p>Each event has {@code event:} on its own line followed by
     * {@code data:} JSON, terminated by an empty line — exactly what the
     * Anthropic Messages API streaming contract emits.
     */
    private static String thinkingThenTextStream() {
        return """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_x","model":"claude-sonnet-4-6"}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me think. "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"The answer is 42."}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sigABC"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"42"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_stop
                data: {"type":"message_stop"}

                """;
    }

    private AuraBotChatService buildService() {
        return new AuraBotChatService(
                mock(LlmProviderFactory.class),
                mock(PromptTemplateService.class),
                mock(ChatToolResolver.class),
                mock(ChatToolExecutor.class),
                mock(ChatSessionStore.class),
                new ObjectMapper(),
                mock(AiTraceService.class),
                mock(MetaModelService.class),
                (Executor) Runnable::run);
    }

    /**
     * Reflective call into the package-private streamAnthropic method —
     * production signature is {@code (baseUrl, apiKey, model, systemPrompt,
     * history, userMessage, maxTokens, temperature, ResponseSink)}.
     */
    private void invokeStreamAnthropic(AuraBotChatService service, String baseUrl, ResponseSink sink) throws Exception {
        Method method = AuraBotChatService.class.getDeclaredMethod(
                "streamAnthropic",
                String.class, String.class, String.class, String.class,
                List.class, String.class, int.class, double.class, ResponseSink.class);
        method.setAccessible(true);
        method.invoke(service, baseUrl, "test-key", "claude-sonnet-4-6",
                "system", List.of(), "hello", 4096, 0.2, sink);
    }

    // =========================================================================
    // B2: streamAnthropic translates SSE thinking blocks into ResponseSink.onThinking
    // =========================================================================

    @Test
    @DisplayName("streamAnthropic_thinkingBlocks_emitOneOnThinkingPerBlock")
    void streamAnthropic_thinkingBlocks_emitOneOnThinkingPerBlock() throws Exception {
        server.createContext("/v1/messages", exchange -> {
            byte[] bytes = thinkingThenTextStream().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AuraBotChatService service = buildService();
        CapturingSink sink = new CapturingSink();

        invokeStreamAnthropic(service, "http://127.0.0.1:" + port, sink);

        // Exactly one thinking event — the two thinking_delta chunks were
        // accumulated and the signature_delta piggy-backed onto the same block.
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
