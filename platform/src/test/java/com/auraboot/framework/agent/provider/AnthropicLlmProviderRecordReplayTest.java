package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * L2 record-replay contract test for {@link AnthropicLlmProvider} (test-strategy doc
 * item ②, Anthropic follow-up). Same approach as the OpenAI-compatible variant: keep
 * the real provider — its real {@code /v1/messages} request build and its real
 * {@code content[].tool_use} response parsing — and only replay a recorded response
 * over a loopback JDK {@link HttpServer} (zero dependency). Catches the "only fails on
 * a real send" class (wrong request shape, broken tool_use/usage parsing, error
 * handling) for the Anthropic wire format too.
 */
class AnthropicLlmProviderRecordReplayTest {

    /** Recorded Anthropic /v1/messages tool_use response (the cassette). */
    private static final String TOOL_USE_RESPONSE = """
            {
              "id": "msg_rec_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-6",
              "content": [
                {
                  "type": "tool_use",
                  "id": "toolu_01abc",
                  "name": "create_order",
                  "input": { "product": "widget", "quantity": 2 }
                }
              ],
              "stop_reason": "tool_use",
              "usage": { "input_tokens": 120, "output_tokens": 30 }
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> capturedRequestBody = new AtomicReference<>();
    private final AtomicReference<String> capturedApiKeyHeader = new AtomicReference<>();
    private final AtomicReference<String> capturedVersionHeader = new AtomicReference<>();
    private final AtomicReference<String> responseToServe = new AtomicReference<>(TOOL_USE_RESPONSE);
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
        server.createContext("/v1/messages", exchange -> {
            capturedRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedApiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            capturedVersionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
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

    private AnthropicLlmProvider provider() {
        return new AnthropicLlmProvider(WebClient.builder().build(), objectMapper, new SimpleMeterRegistry());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private LlmChatRequest toolRequest() {
        return LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
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
                .maxTokens(1024)
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void serializesAnthropicRequest_andParsesRecordedToolUse() throws Exception {
        LlmChatResponse resp = provider().chat(toolRequest(), "test-anthropic-key", baseUrl());

        // ── response parsing (real convertResponse) ──
        assertEquals("tool_use", resp.getStopReason());
        LlmChatResponse.ContentBlock toolBlock = resp.getContent().stream()
                .filter(b -> "tool_use".equals(b.getType())).findFirst().orElseThrow();
        assertEquals("create_order", toolBlock.getName());
        assertEquals("toolu_01abc", toolBlock.getId());
        // Anthropic sends input as a JSON object (not a string) — parsed straight to a map.
        assertEquals("widget", toolBlock.getInput().get("product"));
        assertEquals(2, ((Number) toolBlock.getInput().get("quantity")).intValue());
        assertEquals(120, resp.getInputTokens());

        // ── request serialization (the Anthropic wire format we actually sent) ──
        assertEquals("test-anthropic-key", capturedApiKeyHeader.get(), "must auth via x-api-key, not Bearer");
        assertEquals("2023-06-01", capturedVersionHeader.get());
        Map<String, Object> sent = objectMapper.readValue(capturedRequestBody.get(), Map.class);
        assertEquals("claude-sonnet-4-6", sent.get("model"));
        assertNotNull(sent.get("messages"));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) sent.get("tools");
        assertEquals("create_order", tools.get(0).get("name"), "Anthropic tools are top-level name (not function-nested)");
    }

    @Test
    void emptyContent_parsesGracefully() throws Exception {
        responseToServe.set("""
                { "id":"m","type":"message","role":"assistant","model":"claude-sonnet-4-6",
                  "content":[], "stop_reason":"end_turn", "usage":{"input_tokens":5,"output_tokens":0} }
                """);
        LlmChatResponse resp = provider().chat(toolRequest(), "k", baseUrl());
        assertEquals("end_turn", resp.getStopReason());
        assertTrue(resp.getContent().isEmpty());
    }

    @Test
    void httpError_surfacesAsException() {
        statusToServe.set(429);
        responseToServe.set("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}");
        assertThrows(Exception.class, () -> provider().chat(toolRequest(), "k", baseUrl()));
    }
}
