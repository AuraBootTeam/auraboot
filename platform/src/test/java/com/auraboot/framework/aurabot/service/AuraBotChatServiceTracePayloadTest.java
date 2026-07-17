package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.agent.dto.ChatRequest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Flux;

class AuraBotChatServiceTracePayloadTest {

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("prompt span output keeps full prompt text alongside char count")
    void buildPromptSpanOutput_keepsFullPromptText() {
        String prompt = "system prompt body";

        Map<String, Object> payload = AuraBotChatService.buildPromptSpanOutput(prompt);

        assertThat(payload).containsEntry("system_prompt", prompt);
        assertThat(payload).containsEntry("char_count", prompt.length());
    }

    @Test
    @DisplayName("resolve tools span keeps inspectable request and selected tools")
    void buildResolveToolsPayloads_keepUsefulDetails() {
        Map<String, Object> input = AuraBotChatService.buildResolveToolsSpanInput(
                "find accounts", "crm_account", "rec_001");
        Map<String, Object> output = AuraBotChatService.buildResolveToolsSpanOutput(List.of(
                LlmChatRequest.Tool.builder()
                        .name("lookup_account")
                        .description("Lookup account")
                        .build()));

        assertThat(input).containsEntry("message", "find accounts");
        assertThat(input).containsEntry("model_code", "crm_account");
        assertThat(output).containsEntry("tool_count", 1);
        assertThat((List<?>) output.get("tools")).hasSize(1);
    }

    @Test
    @DisplayName("provider stream hides split think blocks from visible SSE events")
    void providerStreamHidesSplitThinkBlocksFromVisibleSseEvents() {
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
        options.setProvider("openai");
        request.setOptions(options);

        when(factory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("test-key")
                        .baseUrl("stub://local")
                        .defaultModel("MiniMax-M2.5")
                        .maxTokens(128)
                        .build());
        when(chatToolResolver.resolveTools(eq("hello"), eq(null), eq(null), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(List.of(), null, null, true));
        when(factory.getProvider(eq("openai"))).thenReturn(provider);
        when(provider.streamChat(any(), eq("test-key"), eq("stub://local")))
                .thenReturn(Flux.just(
                        LlmChunk.delta(0, "Visible <thi"),
                        LlmChunk.delta(1, "nk>hidden"),
                        LlmChunk.delta(2, " reasoning</thi"),
                        LlmChunk.delta(3, "nk> answer"),
                        LlmChunk.done(4, LlmChatResponse.builder()
                                .stopReason("end_turn")
                                .content(List.of(LlmChatResponse.ContentBlock.builder()
                                        .type("text")
                                        .text("Visible <think>hidden reasoning</think> answer")
                                        .build()))
                                .build())));

        CapturingResponseSink sink = new CapturingResponseSink();
        service.executeAuraBotTurn(TurnContext.legacyDefault(1L, 100L, 100L), request, sink);

        List<CapturedEvent> visibleEvents = sink.events.stream()
                .filter(event -> "chunk".equals(event.name) || "done".equals(event.name))
                .toList();
        assertThat(visibleEvents)
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.payload)
                        .doesNotContain("<think>", "</think>", "hidden", "reasoning"));
        assertThat(visibleEvents.stream().map(CapturedEvent::payload).toList().toString())
                .contains("Visible ", "answer");
    }

    private static class CapturingResponseSink implements ResponseSink {
        final List<CapturedEvent> events = new ArrayList<>();

        @Override
        public void onTextChunk(String text) {
            events.add(new CapturedEvent("chunk", text == null ? "" : text));
        }

        @Override
        public void onToolStart(String toolId, String toolName, Map<String, Object> input) {}

        @Override
        public void onToolResult(String toolId, Map<String, Object> result, boolean success) {}

        @Override
        public void onConfirmRequired(String toolId, String toolName, String description,
                                       Map<String, Object> input, String sessionId) {}

        @Override
        public void onError(String message, String traceId) {
            events.add(new CapturedEvent("error", message == null ? "" : message));
        }

        @Override
        public void onDone(String finalResponse, String traceId) {
            events.add(new CapturedEvent("done", finalResponse == null ? "" : finalResponse));
        }
    }

    private record CapturedEvent(String name, String payload) {}
}
