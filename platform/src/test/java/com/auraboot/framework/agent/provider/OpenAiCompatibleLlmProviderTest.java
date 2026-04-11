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
}
