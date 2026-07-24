package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for OpenAiCompatibleLlmProvider — specifically the tool-support
 * compatibility logic that strips tools for providers like MiniMax.
 */
class OpenAiCompatibleLlmProviderTest {

    /**
     * Reflective access to the private isToolUnsupportedProvider method for testing.
     */
    private boolean isToolUnsupported(OpenAiCompatibleLlmProvider provider, String model) throws Exception {
        Method m = OpenAiCompatibleLlmProvider.class.getDeclaredMethod("isToolUnsupportedProvider", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, model);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertMessage(OpenAiCompatibleLlmProvider provider, LlmChatRequest.Message message)
            throws Exception {
        Method m = OpenAiCompatibleLlmProvider.class.getDeclaredMethod("convertMessageToOpenAi", LlmChatRequest.Message.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(provider, message);
    }

    private OpenAiCompatibleLlmProvider createProvider() {
        // WebClient is not needed for the method under test; pass null (won't be called)
        return new OpenAiCompatibleLlmProvider(null, new ObjectMapper());
    }

    @Test
    void chatRejectsPrivateBaseUrlBeforeAnyHttpCall() {
        // SEC-20260723-05: baseUrl is tenant-configurable (CloudConfig). A value pointing
        // at an internal/loopback address must be rejected by the SSRF guard BEFORE the
        // WebClient is touched. The provider is built with a null WebClient, so if the guard
        // did NOT fire first this would surface as a NullPointerException instead.
        OpenAiCompatibleLlmProvider provider = createProvider(); // webClient == null
        LlmChatRequest request = LlmChatRequest.builder()
                .model("gpt-4o")
                .maxTokens(16)
                .messages(List.of(LlmChatRequest.Message.text("user", "hi")))
                .build();

        assertThatThrownBy(() -> provider.chat(request, "sk-test", "http://127.0.0.1:8080"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minimaxModelsAreToolSupported() throws Exception {
        // MiniMax-M2.5+ supports OpenAI-compatible function calling
        OpenAiCompatibleLlmProvider provider = createProvider();

        assertThat(isToolUnsupported(provider, "MiniMax-M2.5")).isFalse();
        assertThat(isToolUnsupported(provider, "MiniMax-Text-01")).isFalse();
        assertThat(isToolUnsupported(provider, "minimax-m2.5")).isFalse();
        assertThat(isToolUnsupported(provider, "abab6.5s-chat")).isFalse();
        assertThat(isToolUnsupported(provider, "ABAB7-chat")).isFalse();
    }

    @Test
    void openaiAndOtherModelsAreToolSupported() throws Exception {
        OpenAiCompatibleLlmProvider provider = createProvider();

        assertThat(isToolUnsupported(provider, "gpt-4o")).isFalse();
        assertThat(isToolUnsupported(provider, "gpt-4o-mini")).isFalse();
        assertThat(isToolUnsupported(provider, "deepseek-chat")).isFalse();
        assertThat(isToolUnsupported(provider, "qwen-plus")).isFalse();
        assertThat(isToolUnsupported(provider, "glm-4")).isFalse();
        assertThat(isToolUnsupported(provider, "moonshot-v1-8k")).isFalse();
    }

    @Test
    void nullModelIsToolSupported() throws Exception {
        OpenAiCompatibleLlmProvider provider = createProvider();

        assertThat(isToolUnsupported(provider, null)).isFalse();
    }

    @Test
    void estimateCostForMinimax() {
        OpenAiCompatibleLlmProvider provider = createProvider();

        double cost = provider.estimateCost("MiniMax-M2.5", 1000, 500);
        // MiniMax rates: input=1.0, output=4.0 per 1M tokens
        // (1000 * 1.0 + 500 * 4.0) / 1_000_000 = 3000 / 1_000_000 = 0.003
        assertThat(cost).isEqualTo(0.003);
    }

    @Test
    void assistantContentBlocksSerializeOpenAiToolCalls() throws Exception {
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest.ContentBlock block = LlmChatRequest.ContentBlock.builder()
                .type("tool_use")
                .id("call-1")
                .name("nq_supplier_options")
                .input(Map.of("productId", "P-100"))
                .build();

        Map<String, Object> message = convertMessage(provider, LlmChatRequest.Message.builder()
                .role("assistant")
                .content(List.of(block))
                .build());

        assertThat(message).containsEntry("role", "assistant");
        assertThat(message).containsKey("tool_calls");
        assertThat(message.get("tool_calls").toString()).contains("call-1", "nq_supplier_options", "P-100");
    }

    @Test
    void toolResultContentBlockUsesResultPayloadForOpenAiToolMessage() throws Exception {
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest.ContentBlock block = LlmChatRequest.ContentBlock.builder()
                .type("tool_result")
                .toolUseId("call-1")
                .result("{\"success\":true,\"records\":[{\"supplier\":\"Acme\"}]}")
                .build();

        Map<String, Object> message = convertMessage(provider, LlmChatRequest.Message.builder()
                .role("user")
                .content(List.of(block))
                .build());

        assertThat(message).containsEntry("role", "tool");
        assertThat(message).containsEntry("tool_call_id", "call-1");
        assertThat(message.get("content").toString()).contains("Acme");
    }

    @Test
    void toolResultObjectPayloadSerializesAsJsonForOpenAiToolMessage() throws Exception {
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest.ContentBlock block = LlmChatRequest.ContentBlock.builder()
                .type("tool_result")
                .toolUseId("call-1")
                .result(Map.of(
                        "success", true,
                        "records", List.of(Map.of("supplier", "Acme"))))
                .build();

        Map<String, Object> message = convertMessage(provider, LlmChatRequest.Message.builder()
                .role("user")
                .content(List.of(block))
                .build());

        assertThat(message).containsEntry("role", "tool");
        assertThat(message).containsEntry("tool_call_id", "call-1");
        assertThat(message.get("content").toString())
                .contains("\"success\":true")
                .contains("\"supplier\":\"Acme\"");
    }

    @Test
    void buildRequestBodyIncludesRequiredToolChoiceWhenToolsArePresent() throws Exception {
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest request = LlmChatRequest.builder()
                .model("gpt-4o")
                .maxTokens(256)
                .systemPrompt("Use tools before answering.")
                .messages(List.of(LlmChatRequest.Message.text("user", "Compare suppliers.")))
                .tools(List.of(LlmChatRequest.Tool.builder()
                        .name("nq_supplier_options")
                        .description("Supplier options")
                        .inputSchema(Map.of("type", "object", "properties", Map.of()))
                        .build()))
                .toolChoice("required")
                .build();

        Map<String, Object> body = provider.buildOpenAiRequestBody(request);

        assertThat(body).containsEntry("tool_choice", "required");
        assertThat(body).containsKey("tools");
    }

    @Test
    void buildRequestBodyOmitsToolChoiceWhenNoToolsAreSent() throws Exception {
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest request = LlmChatRequest.builder()
                .model("gpt-4o")
                .maxTokens(256)
                .messages(List.of(LlmChatRequest.Message.text("user", "Hello.")))
                .toolChoice("required")
                .build();

        Map<String, Object> body = provider.buildOpenAiRequestBody(request);

        assertThat(body).doesNotContainKey("tool_choice");
        assertThat(body).doesNotContainKey("tools");
    }

    @Test
    void buildRequestBodyMapsJsonObjectResponseFormat() throws Exception {
        OpenAiCompatibleLlmProvider provider = createProvider();
        LlmChatRequest request = LlmChatRequest.builder()
                .model("deepseek-chat")
                .maxTokens(256)
                .responseFormat("json_object")
                .messages(List.of(LlmChatRequest.Message.text("user", "Return JSON.")))
                .build();

        Map<String, Object> body = provider.buildOpenAiRequestBody(request);

        assertThat(body).containsEntry("response_format", Map.of("type", "json_object"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildRequestBodySanitizesCommandCodeToolNames() throws Exception {
        // AuraBoot command tools are named with command codes ("plugin:command"),
        // but OpenAI/DeepSeek reject a ':' (400 — name must match ^[a-zA-Z0-9_-]+$).
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest request = LlmChatRequest.builder()
                .model("deepseek-chat")
                .maxTokens(256)
                .messages(List.of(LlmChatRequest.Message.text("user", "Create a lead.")))
                .tools(List.of(LlmChatRequest.Tool.builder()
                        .name("sales_lead_crm:create_sales_lead")
                        .description("Create a sales lead")
                        .inputSchema(Map.of("type", "object", "properties", Map.of()))
                        .build()))
                .build();

        Map<String, Object> body = provider.buildOpenAiRequestBody(request);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        Map<String, Object> fn = (Map<String, Object>) tools.get(0).get("function");
        String wireName = (String) fn.get("name");

        assertThat(wireName).matches("[a-zA-Z0-9_-]+");
        assertThat(wireName).isEqualTo("sales_lead_crm_create_sales_lead");
    }

    @Test
    void sanitizeToolNameReplacesInvalidCharsAndIsNullSafe() {
        assertThat(OpenAiCompatibleLlmProvider.sanitizeToolName("a:b.c d")).isEqualTo("a_b_c_d");
        assertThat(OpenAiCompatibleLlmProvider.sanitizeToolName("already_valid-1")).isEqualTo("already_valid-1");
        assertThat(OpenAiCompatibleLlmProvider.sanitizeToolName(null)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildRequestBodyNormalizesEmptyToolParametersToObjectSchema() throws Exception {
        // A tool with an empty/typeless inputSchema makes DeepSeek 400
        // ("schema must be 'type: object', got 'type: null'").
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest request = LlmChatRequest.builder()
                .model("deepseek-chat")
                .maxTokens(256)
                .messages(List.of(LlmChatRequest.Message.text("user", "Run it.")))
                .tools(List.of(LlmChatRequest.Tool.builder()
                        .name("platform_execute_sql")
                        .description("Execute SQL")
                        .inputSchema(Map.of()) // empty — the real ToolDiscoveryPort case
                        .build()))
                .build();

        Map<String, Object> body = provider.buildOpenAiRequestBody(request);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        Map<String, Object> params = (Map<String, Object>) ((Map<String, Object>) tools.get(0).get("function")).get("parameters");
        assertThat(params).containsEntry("type", "object");
        assertThat(params).containsKey("properties");
    }

    @Test
    void normalizeToolParametersEnsuresObjectType() {
        assertThat(OpenAiCompatibleLlmProvider.normalizeToolParameters(null))
                .containsEntry("type", "object");
        assertThat(OpenAiCompatibleLlmProvider.normalizeToolParameters(Map.of()))
                .containsEntry("type", "object");
        // non-empty but type-less: add type, preserve existing keys
        Map<String, Object> typeless = OpenAiCompatibleLlmProvider.normalizeToolParameters(
                Map.of("properties", Map.of("q", Map.of("type", "string"))));
        assertThat(typeless).containsEntry("type", "object");
        assertThat(typeless).containsKey("properties");
        // already valid: returned as-is
        Map<String, Object> valid = Map.of("type", "object", "properties", Map.of());
        assertThat(OpenAiCompatibleLlmProvider.normalizeToolParameters(valid)).isSameAs(valid);
    }

    @Test
    @SuppressWarnings("unchecked")
    void multipleToolResultsInOneMessageBecomeSeparateOpenAiToolMessages() throws Exception {
        // StepLoopService packs ALL tool results of one round into a single role:user message
        // (Anthropic style). OpenAI-compatible providers (DeepSeek) require a separate role:tool
        // message per tool_call_id; emitting only the first → 400 "tool_calls must be followed by
        // tool messages responding to each tool_call_id".
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest.Message assistant = LlmChatRequest.Message.builder()
                .role("assistant")
                .content(List.of(
                        Map.of("type", "tool_use", "id", "call-1", "name", "get_a", "input", Map.of()),
                        Map.of("type", "tool_use", "id", "call-2", "name", "get_b", "input", Map.of())))
                .build();
        LlmChatRequest.Message toolResults = LlmChatRequest.Message.builder()
                .role("user")
                .content(List.of(
                        Map.of("type", "tool_result", "tool_use_id", "call-1", "content", "result-A"),
                        Map.of("type", "tool_result", "tool_use_id", "call-2", "content", "result-B")))
                .build();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("deepseek-chat").maxTokens(256)
                .messages(List.of(assistant, toolResults))
                .build();

        Method m = OpenAiCompatibleLlmProvider.class.getDeclaredMethod(
                "buildOpenAiRequestBody", LlmChatRequest.class);
        m.setAccessible(true);
        Map<String, Object> body = (Map<String, Object>) m.invoke(provider, req);
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");

        List<Object> toolCallIds = messages.stream()
                .filter(x -> "tool".equals(x.get("role")))
                .map(x -> x.get("tool_call_id"))
                .toList();
        assertThat(toolCallIds).containsExactly("call-1", "call-2");
    }
}
