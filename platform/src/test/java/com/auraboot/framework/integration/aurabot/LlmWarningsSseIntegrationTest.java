package com.auraboot.framework.integration.aurabot;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.aurabot.service.ChatSessionStore;
import com.auraboot.framework.aurabot.service.ChatToolExecutor;
import com.auraboot.framework.aurabot.service.ChatToolResolver;
import com.auraboot.framework.aurabot.service.PromptTemplateService;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.SseResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D.2 (ACP P0/P1 follow-up) — provider-side warnings collected on
 * {@link LlmChatResponse#getWarnings()} (e.g. Anthropic's
 * {@code max_tokens} auto-extension when Extended Thinking budget exceeds
 * the caller's value) must surface to the SSE stream as a dedicated
 * {@code warning} event so the frontend can toast them. Previously they
 * lived only in {@code log.warn} on the server.
 *
 * <p>Verifies (without booting Spring — same lightweight pattern as
 * {@code AuraBotChatServiceThinkingIntegrationTest}; the warnings flow does
 * not touch DB / Redis so {@link com.auraboot.framework.integration.BaseIntegrationTest}
 * would only add boot-time cost without buying coverage):
 * <ul>
 *   <li>{@link SseResponseSink#onWarnings(List)} emits an SSE event named
 *       {@code "warning"} with a JSON-string payload containing the warnings
 *       array — the wire contract the frontend's
 *       {@code processSSEStream} switch-case matches on.</li>
 *   <li>{@code SseResponseSink.onWarnings} is a no-op when the list is
 *       null/empty, so the chokepoint cannot accidentally spam empty
 *       {@code warning} frames.</li>
 *   <li>The chokepoint ({@code AuraBotChatService} resume path) calls
 *       {@code sink.onWarnings(...)} for every {@link LlmProvider#chat
 *       provider.chat()} round whose response carries a non-empty
 *       {@code warnings} list, BEFORE the corresponding {@code done} so the
 *       frontend renders the toast while the assistant message lands.</li>
 * </ul>
 */
class LlmWarningsSseIntegrationTest {

    // =========================================================================
    // SSE wire contract — SseResponseSink.onWarnings → event:warning + JSON body
    // =========================================================================

    @Test
    @DisplayName("sseResponseSink_onWarnings_emitsWarningEventWithJsonPayload")
    void sseResponseSink_onWarnings_emitsWarningEventWithJsonPayload() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseResponseSink sink = new SseResponseSink(emitter, new ObjectMapper());

        sink.onWarnings(List.of(
                "Extended Thinking budget requires max_tokens auto-extended to 14000.",
                "Cache write at 1.25x rate."));

        assertThat(emitter.events)
                .as("SSE event name must be 'warning' — frontend processSSEStream "
                        + "switch-case matches on currentEvent === 'warning'")
                .anySatisfy(event -> {
                    assertThat(event.name).isEqualTo("warning");
                    // JSON-string body shape: {"warnings":["...","..."]}
                    assertThat(event.payload).contains("\"warnings\"");
                    assertThat(event.payload).contains("Extended Thinking budget");
                    assertThat(event.payload).contains("Cache write at 1.25x rate.");
                });
    }

    @Test
    @DisplayName("sseResponseSink_onWarnings_emitsNothingWhenListIsEmpty")
    void sseResponseSink_onWarnings_emitsNothingWhenListIsEmpty() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseResponseSink sink = new SseResponseSink(emitter, new ObjectMapper());

        sink.onWarnings(List.of());
        sink.onWarnings(null);

        assertThat(emitter.events)
                .as("empty / null warnings must NOT produce a wire event "
                        + "(red-line: no toast spam when there is nothing to say)")
                .isEmpty();
    }

    // =========================================================================
    // Chokepoint integration — AuraBotChatService.resume calls sink.onWarnings
    // when provider.chat() returns a non-empty LlmChatResponse.warnings.
    // =========================================================================

    @Test
    @DisplayName("resumeApprovedTurn_providerWarnings_forwardedToSink_beforeDone")
    void resumeApprovedTurn_providerWarnings_forwardedToSink_beforeDone() {
        // --- Stub LlmProvider returning a fixed response with warnings ---
        String warningText = "Extended Thinking budget (10000) requires max_tokens >= 11024; "
                + "auto-extended to 14000.";
        LlmProvider stubProvider = stubProviderReturning(LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text")
                        .text("Done.")
                        .build()))
                .warnings(List.of(warningText))
                .build());

        LlmProviderFactory llmProviderFactory = mock(LlmProviderFactory.class);
        when(llmProviderFactory.getProvider("anthropic")).thenReturn(stubProvider);

        // --- Stub ChatToolResolver / Executor / SessionStore / Trace ---
        ChatToolResolver toolResolver = mock(ChatToolResolver.class);
        when(toolResolver.resolveTools(any(), any(), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(List.of(), null, null, true));
        ChatToolExecutor toolExecutor = mock(ChatToolExecutor.class);
        when(toolExecutor.execute(anyString(), any(), any()))
                .thenReturn(Map.of("success", true, "data", "ok"));

        AiTraceService traceService = mock(AiTraceService.class);
        when(traceService.findActiveTrace(any())).thenReturn(null);
        when(traceService.startSpan(any(), any(), anyString(), anyString(), any())).thenReturn(null);

        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                mock(PromptTemplateService.class),
                toolResolver,
                toolExecutor,
                mock(ChatSessionStore.class),
                new ObjectMapper(),
                traceService,
                mock(MetaModelService.class),
                (Executor) Runnable::run);
        setMaxToolRounds(service, 20);

        // --- Build a TurnContext + PendingTool that bypass tool confirmation ---
        TurnContext ctx = new TurnContext(
                "tn_test_warnings",
                /*tenantId*/ 1L,
                /*userId*/ 1L,
                /*humanMemberId*/ null,
                /*agentId*/ null,
                /*agentCode*/ "aurabot",
                /*channelSessionId*/ null,
                /*conversationId*/ null,
                /*inboundMessageId*/ null,
                /*triageBucket*/ null,
                /*traceId*/ null,
                /*taskPid*/ null,
                Instant.now());

        ChatSessionStore.PendingTool pending = ChatSessionStore.PendingTool.builder()
                .turnId("tn_test_warnings")
                .tenantId(1L)
                .userId(1L)
                .agentCode("aurabot")
                .sessionId("sess_test")
                .toolId("tool_1")
                .toolName("dsl_query")
                .input(Map.of("modelCode", "demo"))
                .messages(new ArrayList<>())
                .providerCode("anthropic")
                .apiKey("test-key")
                .baseUrl("http://localhost")
                .model("claude-sonnet-4-6")
                .systemPrompt("system")
                .maxTokens(4096)
                .currentLoop(0)
                .build();

        // --- Drive the resume path with a CapturingSink ---
        CapturingSink sink = new CapturingSink();
        TurnOutcome outcome = service.resumeApprovedTurnFromPending(ctx, pending, sink);

        assertThat(outcome)
                .as("end_turn round should drive a Success outcome — warnings must NOT short-circuit")
                .isInstanceOf(TurnOutcome.Success.class);
        assertThat(sink.warnings)
                .as("provider.chat() returned LlmChatResponse.warnings → sink.onWarnings(...) must be called")
                .hasSize(1);
        assertThat(sink.warnings.get(0)).containsExactly(warningText);

        // Order check: the warning event must precede the terminal `done` so
        // the frontend toasts while/before rendering the final assistant text.
        assertThat(sink.events).contains("warning", "done");
        int warningIdx = sink.events.indexOf("warning");
        int doneIdx = sink.events.indexOf("done");
        assertThat(warningIdx)
                .as("warning event must be emitted BEFORE done so the toast renders "
                        + "alongside the final assistant message, not after stream close")
                .isLessThan(doneIdx);
    }

    @Test
    @DisplayName("resumeApprovedTurn_noWarnings_doesNotCallOnWarnings")
    void resumeApprovedTurn_noWarnings_doesNotCallOnWarnings() {
        LlmProvider stubProvider = stubProviderReturning(LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text").text("Done.").build()))
                // warnings == null  →  must NOT trigger sink.onWarnings
                .build());

        LlmProviderFactory llmProviderFactory = mock(LlmProviderFactory.class);
        when(llmProviderFactory.getProvider("anthropic")).thenReturn(stubProvider);

        ChatToolResolver toolResolver = mock(ChatToolResolver.class);
        when(toolResolver.resolveTools(any(), any(), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(List.of(), null, null, true));
        ChatToolExecutor toolExecutor = mock(ChatToolExecutor.class);
        when(toolExecutor.execute(anyString(), any(), any()))
                .thenReturn(Map.of("success", true));
        AiTraceService traceService = mock(AiTraceService.class);
        when(traceService.findActiveTrace(any())).thenReturn(null);
        when(traceService.startSpan(any(), any(), anyString(), anyString(), any())).thenReturn(null);

        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                mock(PromptTemplateService.class),
                toolResolver,
                toolExecutor,
                mock(ChatSessionStore.class),
                new ObjectMapper(),
                traceService,
                mock(MetaModelService.class),
                (Executor) Runnable::run);
        setMaxToolRounds(service, 20);

        TurnContext ctx = new TurnContext(
                "tn_no_warnings", 1L, 1L, null, null, "aurabot",
                null, null, null, null, null, null, Instant.now());
        ChatSessionStore.PendingTool pending = ChatSessionStore.PendingTool.builder()
                .turnId("tn_no_warnings").tenantId(1L).userId(1L).agentCode("aurabot")
                .sessionId("sess_test").toolId("tool_1").toolName("dsl_query")
                .input(Map.of()).messages(new ArrayList<>())
                .providerCode("anthropic").apiKey("k").baseUrl("http://localhost")
                .model("claude-sonnet-4-6").systemPrompt("system")
                .maxTokens(4096).currentLoop(0).build();

        CapturingSink sink = new CapturingSink();
        service.resumeApprovedTurnFromPending(ctx, pending, sink);

        assertThat(sink.warnings)
                .as("response.warnings == null → sink.onWarnings must not be invoked "
                        + "(red-line: no toast spam when nothing to surface)")
                .isEmpty();
    }

    // =========================================================================
    // Test fixtures
    // =========================================================================

    /**
     * Anonymous-class stub of {@link LlmProvider} (the interface has multiple
     * abstract methods so it is not a functional interface). Returns the same
     * fixed response for every call — sufficient because the resume path only
     * runs one round when {@code stopReason == "end_turn"}.
     */
    /**
     * Inject a non-zero {@code maxToolRounds} so the resume-loop {@code remainingRounds}
     * computation does not short-circuit to "Tool loop exceeded maximum rounds" when
     * Spring's {@code @Value("${aurabot.max-tool-rounds:20}")} default is unavailable
     * (we don't boot Spring here — see class javadoc).
     */
    private static void setMaxToolRounds(AuraBotChatService service, int rounds) {
        try {
            Field f = AuraBotChatService.class.getDeclaredField("maxToolRounds");
            f.setAccessible(true);
            f.setInt(service, rounds);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static LlmProvider stubProviderReturning(LlmChatResponse fixed) {
        return new LlmProvider() {
            @Override public String getProviderCode() { return "anthropic"; }
            @Override public String getDisplayName() { return "Stub Anthropic"; }
            @Override public boolean supportsTools() { return true; }
            @Override public LlmChatResponse chat(LlmChatRequest request, String apiKey, String baseUrl) {
                return fixed;
            }
            @Override public double estimateCost(String model, int inputTokens, int outputTokens) { return 0; }
            @Override public String getDefaultBaseUrl() { return "http://localhost"; }
            @Override public String getDefaultModel() { return "claude-sonnet-4-6"; }
        };
    }

    private static class CapturingSink implements ResponseSink {
        final List<String> events = new ArrayList<>();
        final List<List<String>> warnings = new ArrayList<>();

        @Override public void onTextChunk(String text) { events.add("chunk"); }
        @Override public void onToolStart(String toolId, String toolName, Map<String, Object> input) { events.add("tool_start"); }
        @Override public void onToolResult(String toolId, Map<String, Object> result, boolean success) { events.add("tool_result"); }
        @Override public void onConfirmRequired(String toolId, String toolName, String description,
                                                 Map<String, Object> input, String pendingTurnId) { events.add("confirm_required"); }
        @Override public void onError(String message, String traceId) { events.add("error"); }
        @Override public void onDone(String finalResponse, String traceId) { events.add("done"); }
        @Override public void onThinking(String content, int tokens, String signature) { events.add("thinking"); }
        @Override public void onResultContract(ResultContract contract) { events.add("result_contract"); }
        @Override public void onWarnings(List<String> w) {
            events.add("warning");
            warnings.add(List.copyOf(w));
        }
    }

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
            events.add(new EmittedEvent(name, payload.toString()));
        }
    }

    private record EmittedEvent(String name, String payload) {}
}
