package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.FormFillRequest;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** External provider wire fixture only; no paid model request is made. */
class FormFillProviderContractTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothWireProtocolsPreserveTheClosedFormSchemaAndDecodeDraftFacts(boolean anthropic) throws Exception {
        var mapper = new ObjectMapper();
        var capture = new AtomicReference<String>();
        var form = new FormFillRequest("f", "customer", "2026-09-14", "UTC", List.of(
                new FormFillRequest.Field("name", "Name", "string", null, null, false),
                new FormFillRequest.Field("count", "Count", "integer", null, null, false)));
        var schema = FormFillContract.schema(form);
        var facts = Map.of("fields", Map.of("name", "Acme", "count", 2), "reviews", Map.of(
                "name", Map.of("status", "supported", "quote", "Acme"),
                "count", Map.of("status", "supported", "quote", "2")));
        String response = mapper.writeValueAsString(anthropic
                ? Map.of("id", "fixture-msg", "type", "message", "role", "assistant", "model", "fixture",
                        "stop_reason", "tool_use", "usage", Map.of("input_tokens", 12, "output_tokens", 8),
                        "content", List.of(Map.of("type", "tool_use", "id", "fixture-call",
                                "name", "platform_fill_form", "input", facts)))
                : Map.of("id", "fixture-msg", "model", "fixture", "usage", Map.of("prompt_tokens", 12, "completion_tokens", 8),
                        "choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of("role", "assistant",
                                "tool_calls", List.of(Map.of("id", "fixture-call", "type", "function", "function",
                                        Map.of("name", "platform_fill_form", "arguments", mapper.writeValueAsString(facts)))))))));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(anthropic ? "/v1/messages" : "/v1/chat/completions", exchange -> {
            capture.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        String prior = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1");
        server.start();
        var registry = new SimpleMeterRegistry();
        try {
            LlmProvider provider = anthropic
                    ? new AnthropicLlmProvider(WebClient.create(), mapper, registry)
                    : new OpenAiCompatibleLlmProvider(WebClient.create(), mapper);
            var request = LlmChatRequest.builder().model("fixture").maxTokens(1024)
                    .systemPrompt("Extract supported facts into a draft.")
                    .messages(List.of(LlmChatRequest.Message.builder().role("user").content("Acme, 2").build()))
                    .tools(new ChatToolResolver(null, null, null).resolveFormFill(form).tools()).build();
            var result = provider.chat(request, "fixture-only", "http://127.0.0.1:" + server.getAddress().getPort());
            var sent = mapper.readTree(capture.get());
            var tool = sent.path("tools").get(0);
            assertThat(anthropic ? tool.path("input_schema") : tool.path("function").path("parameters"))
                    .isEqualTo(mapper.valueToTree(schema));
            assertThat(result.getStopReason()).isEqualTo("tool_use");
            var call = result.getContent().stream().filter(c -> "tool_use".equals(c.getType())).findFirst().orElseThrow();
            assertThat(call.getName()).isEqualTo("platform_fill_form");
            assertThat(call.getInput()).isEqualTo(facts);
            FormFillContract.validate(schema, call.getInput(), "Acme, 2");
        } finally {
            registry.close();
            server.stop(0);
            if (prior == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
            else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", prior);
        }
    }
}
