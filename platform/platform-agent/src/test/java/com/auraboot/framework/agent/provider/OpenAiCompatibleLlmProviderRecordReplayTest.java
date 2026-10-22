package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 record-replay contract test (test-strategy doc
 * {@code docs/backlog/2026-06-12-agent-testing-strategy-and-eval-loop.md}, item ②).
 *
 * <p>Where L1 stub replaces the whole provider (returns {@code [stub response]},
 * bypassing the real request/response path) and L3 makes a live model call, this
 * layer keeps the <strong>real</strong> {@link OpenAiCompatibleLlmProvider} —
 * its real OpenAI-format request serialization and its real {@code tool_calls}
 * response parsing — and only replaces the network with a recorded response served
 * by a loopback JDK {@link HttpServer} (the codebase convention; no
 * MockWebServer/WireMock dependency). This is the cheap, deterministic way to catch
 * the "only fails on a real send" class of bugs (wrong request shape, broken
 * tool-call/arguments parsing, finish-reason mapping, error handling) at stub cost.
 *
 * <p>The cassette below is a representative DeepSeek/OpenAI-compatible tool-call
 * response. Refresh it by capturing a real response when a provider changes its
 * wire format.
 */
class OpenAiCompatibleLlmProviderRecordReplayTest {

    /** Recorded OpenAI-compatible tool-call response (the "cassette"). */
    private static final String TOOL_CALL_RESPONSE = """
            {
              "id": "chatcmpl-rec-001",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "deepseek-chat",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_0_abc",
                        "type": "function",
                        "function": {
                          "name": "create_order",
                          "arguments": "{\\"product\\":\\"widget\\",\\"quantity\\":2}"
                        }
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ],
              "usage": { "prompt_tokens": 142, "completion_tokens": 18, "total_tokens": 160 }
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> capturedRequestBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> responseToServe = new AtomicReference<>(TOOL_CALL_RESPONSE);
    private final AtomicInteger statusToServe = new AtomicInteger(200);
    private String priorSsrfAllow;

    @BeforeEach
    void startServer() throws IOException {
        // The provider now runs an SSRF guard over baseUrl (SEC-20260723-05), which
        // refuses loopback by default. This test legitimately fronts a 127.0.0.1 mock,
        // so opt loopback into the explicit allowlist for the duration of the test.
        priorSsrfAllow = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseToServe.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(statusToServe.get(), body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (priorSsrfAllow == null) {
            System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        } else {
            System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", priorSsrfAllow);
        }
    }

    private OpenAiCompatibleLlmProvider provider() {
        return new OpenAiCompatibleLlmProvider(WebClient.builder().build(), objectMapper);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private LlmChatRequest toolRequest() {
        return LlmChatRequest.builder()
                .model("deepseek-chat")
                .systemPrompt("You are an order assistant. Use tools.")
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("Create an order for 2 widgets").build()))
                .tools(List.of(LlmChatRequest.Tool.builder()
                        .name("create_order")
                        .description("Create a new sales order")
                        .inputSchema(Map.of("type", "object", "properties",
                                Map.of("product", Map.of("type", "string"),
                                        "quantity", Map.of("type", "integer"))))
                        .build()))
                .toolChoice("auto")
                .maxTokens(1024)
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void serializesRealOpenAiRequest_andParsesRecordedToolCall() throws Exception {
        LlmChatResponse resp = provider().chat(toolRequest(), "test-key-123", baseUrl());

        // ── response parsing (the real convertResponse path) ──
        assertEquals("tool_use", resp.getStopReason(), "finish_reason=tool_calls must map to tool_use");
        assertNotNull(resp.getContent());
        LlmChatResponse.ContentBlock toolBlock = resp.getContent().stream()
                .filter(b -> "tool_use".equals(b.getType())).findFirst().orElseThrow();
        assertEquals("create_order", toolBlock.getName());
        assertEquals("call_0_abc", toolBlock.getId());
        // arguments was a JSON *string* in the wire format — must be parsed into a map
        assertEquals("widget", toolBlock.getInput().get("product"));
        assertEquals(2, ((Number) toolBlock.getInput().get("quantity")).intValue());
        assertEquals(142, resp.getInputTokens());

        // ── request serialization (what we actually sent on the wire) ──
        assertEquals("Bearer test-key-123", capturedAuthHeader.get());
        Map<String, Object> sent = objectMapper.readValue(capturedRequestBody.get(), Map.class);
        assertEquals("deepseek-chat", sent.get("model"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) sent.get("messages");
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("user", messages.get(1).get("role"));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) sent.get("tools");
        assertEquals("function", tools.get(0).get("type"));
        Map<String, Object> fn = (Map<String, Object>) tools.get(0).get("function");
        assertEquals("create_order", fn.get("name"));
        assertNotNull(fn.get("parameters"), "tool inputSchema must be sent as function.parameters");
        assertEquals("auto", sent.get("tool_choice"));
    }

    @Test
    void emptyChoices_parsesGracefullyAsEndTurn() throws Exception {
        responseToServe.set("{\"choices\":[]}");
        LlmChatResponse resp = provider().chat(toolRequest(), "k", baseUrl());
        assertEquals("end_turn", resp.getStopReason());
        assertTrue(resp.getContent().isEmpty());
    }

    @Test
    void httpError_surfacesAsException_notSilentWrongParse() {
        statusToServe.set(429);
        responseToServe.set("{\"error\":{\"message\":\"rate limited\"}}");
        // The real send must fail loudly (WebClient 4xx), never return a bogus success.
        assertThrows(Exception.class, () -> provider().chat(toolRequest(), "k", baseUrl()));
    }

    @Test
    void malformedJsonBody_surfacesAsException() {
        responseToServe.set("not-json-at-all");
        assertThrows(Exception.class, () -> provider().chat(toolRequest(), "k", baseUrl()));
    }
}
