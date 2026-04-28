package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
