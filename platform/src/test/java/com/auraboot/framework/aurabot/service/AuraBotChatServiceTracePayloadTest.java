package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuraBotChatServiceTracePayloadTest {

    @Test
    @DisplayName("prompt span output keeps full prompt text alongside char count")
    void buildPromptSpanOutput_keepsFullPromptText() {
        String prompt = "system prompt body";

        Map<String, Object> payload = AuraBotChatService.buildPromptSpanOutput(prompt);

        assertThat(payload).containsEntry("system_prompt", prompt);
        assertThat(payload).containsEntry("char_count", prompt.length());
    }

    @Test
    @DisplayName("generation span input keeps model, system prompt, messages and tools")
    void buildGenerationSpanInput_keepsRequestDetails() {
        LlmChatRequest request = LlmChatRequest.builder()
                .model("gpt-test")
                .systemPrompt("system prompt")
                .messages(List.of(
                        LlmChatRequest.Message.builder()
                                .role("user")
                                .content("hello")
                                .build()))
                .tools(List.of(
                        LlmChatRequest.Tool.builder()
                                .name("lookup_account")
                                .description("Lookup account")
                                .inputSchema(Map.of("type", "object"))
                                .build()))
                .maxTokens(2048)
                .build();

        Map<String, Object> payload = AuraBotChatService.buildGenerationSpanInput(request);

        assertThat(payload).containsEntry("model", "gpt-test");
        assertThat(payload).containsEntry("system_prompt", "system prompt");
        assertThat(payload).containsEntry("max_tokens", 2048);
        assertThat((List<?>) payload.get("messages")).hasSize(1);
        assertThat((List<?>) payload.get("tools")).hasSize(1);
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
    @DisplayName("generation span output keeps full response content and token metadata")
    void buildGenerationSpanOutput_keepsResponseDetails() {
        LlmChatResponse response = LlmChatResponse.builder()
                .stopReason("tool_use")
                .inputTokens(321)
                .outputTokens(654)
                .content(List.of(
                        LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("thinking")
                                .build(),
                        LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool_1")
                                .name("lookup_account")
                                .input(Map.of("accountId", "A-001"))
                                .build()))
                .build();

        Map<String, Object> payload = AuraBotChatService.buildGenerationSpanOutput(response);

        assertThat(payload).containsEntry("stop_reason", "tool_use");
        assertThat(payload).containsEntry("input_tokens", 321);
        assertThat(payload).containsEntry("output_tokens", 654);
        assertThat((List<?>) payload.get("content")).hasSize(2);
    }

    @Test
    @DisplayName("tool execution allowlist rejects names that were not exposed to the LLM")
    void isToolOffered_rejectsUnavailableToolName() {
        List<LlmChatRequest.Tool> tools = List.of(
                LlmChatRequest.Tool.builder().name("nq_crm_lead_pipeline_stats").build(),
                LlmChatRequest.Tool.builder().name("platform_fill_form").build());

        assertThat(AuraBotChatService.isToolOffered(tools, "nq_crm_lead_pipeline_stats")).isTrue();
        assertThat(AuraBotChatService.isToolOffered(tools, "platform_execute_sql")).isFalse();
        assertThat(AuraBotChatService.isToolOffered(tools, null)).isFalse();
    }

    @Test
    @DisplayName("OpenAI-compatible stream hides split think blocks from visible SSE events")
    void openAiCompatibleStreamHidesSplitThinkBlocksFromVisibleSseEvents() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = """
                    data: {"choices":[{"delta":{"content":"Visible <thi"}}]}

                    data: {"choices":[{"delta":{"content":"nk>hidden"}}]}

                    data: {"choices":[{"delta":{"content":" reasoning</thi"}}]}

                    data: {"choices":[{"delta":{"content":"nk> answer"}}]}

                    data: [DONE]

                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            AuraBotChatService service = new AuraBotChatService(
                    mock(LlmProviderFactory.class),
                    mock(PromptTemplateService.class),
                    mock(ChatToolResolver.class),
                    mock(ChatToolExecutor.class),
                    mock(ChatSessionStore.class),
                    new ObjectMapper(),
                    mock(AiTraceService.class),
                    mock(MetaModelService.class),
                    (Executor) Runnable::run);
            Method method = AuraBotChatService.class.getDeclaredMethod(
                    "streamOpenAiCompatible",
                    String.class,
                    String.class,
                    String.class,
                    String.class,
                    List.class,
                    String.class,
                    int.class,
                    double.class,
                    ResponseSink.class);
            method.setAccessible(true);

            CapturingResponseSink sink = new CapturingResponseSink();
            method.invoke(
                    service,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-key",
                    "MiniMax-M2.5",
                    "system",
                    List.of(),
                    "hello",
                    128,
                    0.2,
                    sink);

            List<CapturedEvent> visibleEvents = sink.events.stream()
                    .filter(event -> "chunk".equals(event.name) || "done".equals(event.name))
                    .toList();
            assertThat(visibleEvents)
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event.payload)
                            .doesNotContain("<think>", "</think>", "hidden", "reasoning"));
            assertThat(visibleEvents.stream().map(CapturedEvent::payload).toList().toString())
                    .contains("Visible ", "answer");
        } finally {
            server.stop(0);
        }
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
