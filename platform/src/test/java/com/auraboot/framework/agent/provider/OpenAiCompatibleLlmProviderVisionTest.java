package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for OpenAiCompatibleLlmProvider — Vision (P1) refusal path.
 *
 * <p>OpenAI-compatible providers used by this platform (DeepSeek, Qwen, GLM,
 * MiniMax, Sonar, ...) have inconsistent or absent vision support. Until P1.5
 * lands a per-provider matrix, the provider must REFUSE image content with a
 * clear {@link IllegalArgumentException} rather than silently dropping the
 * attachment or fabricating a "[image]" placeholder — both of which would
 * erase the user's intent (no-fallback red line, see CLAUDE.md).
 */
class OpenAiCompatibleLlmProviderVisionTest {

    private static final String SAMPLE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

    private OpenAiCompatibleLlmProvider createProvider() {
        return new OpenAiCompatibleLlmProvider(null, new ObjectMapper());
    }

    @Test
    void chat_imageContent_throwsIllegalArgumentException() {
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("gpt-4o")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.imageBase64(
                        "user", "image/png", SAMPLE_BASE64, "what is this?")))
                .build();

        assertThatThrownBy(() -> provider.chat(req, "fake-key", "https://api.openai.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai-compatible")
                .hasMessageContaining("vision");
    }

    @Test
    void chat_imageUrlContent_throwsIllegalArgumentException() {
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("deepseek-chat")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.imageUrl(
                        "user", "https://example.com/img.jpg", "describe")))
                .build();

        assertThatThrownBy(() -> provider.chat(req, "fake-key", "https://api.deepseek.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vision");
    }

    @Test
    void chat_textOnlyMessage_doesNotTriggerVisionGate() {
        // Sanity check — the gate must not fire on text-only requests. The
        // exception we observe here will come from the WebClient (null in
        // the test ctor) or from a NPE path, NOT from our IllegalArgumentException.
        OpenAiCompatibleLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("gpt-4o")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.text("user", "hello")))
                .build();

        assertThatThrownBy(() -> provider.chat(req, "fake-key", "https://api.openai.com"))
                .isNotInstanceOf(IllegalArgumentException.class);
    }
}
