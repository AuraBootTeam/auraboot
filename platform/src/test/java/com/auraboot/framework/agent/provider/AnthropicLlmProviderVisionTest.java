package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.AnthropicRequest;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for AnthropicLlmProvider — Vision (P1) wire-up.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code MessageContentBlock} with {@code type=image, source.type=base64}
 *       serialises to Anthropic's wire shape ({@code source.media_type} as
 *       snake_case, {@code data} carrying raw base64).</li>
 *   <li>{@code source.type=url} variant serialises with the {@code url} field
 *       and omits {@code media_type}/{@code data}.</li>
 *   <li>Mixed text + image blocks preserve order — image first per Anthropic
 *       prompt-engineering guidance.</li>
 *   <li>{@link AnthropicLlmProvider#chat(LlmChatRequest, String, String)}
 *       throws {@link IllegalArgumentException} when image content is supplied
 *       to a non-vision-capable model. Silent drop is forbidden — see the
 *       no-fallback red line.</li>
 *   <li>{@link AnthropicLlmProvider#supportsVision(String)} matrix matches
 *       documented Anthropic capability surface (3.5+, 4.x; rejects 2.x and
 *       non-Anthropic codes).</li>
 * </ul>
 */
class AnthropicLlmProviderVisionTest {

    private AnthropicLlmProvider createProvider() {
        return new AnthropicLlmProvider(null, new ObjectMapper(), new SimpleMeterRegistry());
    }

    private AnthropicRequest buildRequest(AnthropicLlmProvider provider, LlmChatRequest req) throws Exception {
        Method m = AnthropicLlmProvider.class.getDeclaredMethod("buildAnthropicRequest", LlmChatRequest.class);
        m.setAccessible(true);
        return (AnthropicRequest) m.invoke(provider, req);
    }

    private static final String SAMPLE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

    // =========================================================================
    // capability gate — supportsVision matrix
    // =========================================================================

    @Test
    void supportsVision_correctlyClassifiesModels() {
        AnthropicLlmProvider provider = createProvider();

        // Vision-capable: Claude 3.5 / 4.x / 5.x families
        assertThat(provider.supportsVision("claude-sonnet-4-6")).isTrue();
        assertThat(provider.supportsVision("claude-sonnet-4-7")).isTrue();
        assertThat(provider.supportsVision("claude-opus-4-7")).isTrue();
        assertThat(provider.supportsVision("claude-opus-4")).isTrue();
        assertThat(provider.supportsVision("claude-haiku-4-5")).isTrue();
        assertThat(provider.supportsVision("claude-3-5-sonnet-20241022")).isTrue();
        assertThat(provider.supportsVision("claude-3-5-haiku-20241022")).isTrue();
        assertThat(provider.supportsVision("claude-3-opus-20240229")).isTrue();
        assertThat(provider.supportsVision("claude-3-sonnet-20240229")).isTrue();
        assertThat(provider.supportsVision("claude-3-haiku-20240307")).isTrue();

        // Not vision-capable: Claude 2.x, Claude Instant, non-Anthropic
        assertThat(provider.supportsVision("claude-2")).isFalse();
        assertThat(provider.supportsVision("claude-2.1")).isFalse();
        assertThat(provider.supportsVision("claude-instant-1")).isFalse();
        assertThat(provider.supportsVision("gpt-4")).isFalse();
        assertThat(provider.supportsVision("gpt-4o")).isFalse();
        assertThat(provider.supportsVision("deepseek-chat")).isFalse();
        assertThat(provider.supportsVision(null)).isFalse();
        assertThat(provider.supportsVision("")).isFalse();
        assertThat(provider.supportsVision("   ")).isFalse();
    }

    // =========================================================================
    // convertMessages — image base64 wire-format
    // =========================================================================

    @Test
    void convertMessages_imageBase64Block_serialisedAsAnthropicImageContentBlock() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest.Message msg = LlmChatRequest.Message.imageBase64(
                "user", "image/png", SAMPLE_BASE64, "What is in this image?");

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(msg))
                .build();

        AnthropicRequest out = buildRequest(provider, req);
        assertThat(out.getMessages()).hasSize(1);
        Object content = out.getMessages().get(0).getContent();
        assertThat(content).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<AnthropicRequest.ImageContentBlock> blocks =
                (List<AnthropicRequest.ImageContentBlock>) content;
        assertThat(blocks).hasSize(2);

        AnthropicRequest.ImageContentBlock imgBlock = blocks.get(0);
        assertThat(imgBlock.getType()).isEqualTo("image");
        assertThat(imgBlock.getSource()).isNotNull();
        assertThat(imgBlock.getSource().getType()).isEqualTo("base64");
        assertThat(imgBlock.getSource().getMediaType()).isEqualTo("image/png");
        assertThat(imgBlock.getSource().getData()).isEqualTo(SAMPLE_BASE64);
        assertThat(imgBlock.getSource().getUrl()).isNull();

        // JSON wire check — media_type must be snake_case
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(out);
        assertThat(json).contains("\"type\":\"image\"");
        assertThat(json).contains("\"media_type\":\"image/png\"");
        assertThat(json).contains("\"data\":\"" + SAMPLE_BASE64 + "\"");
        // mediaType (camelCase) must NOT leak into the wire payload
        assertThat(json).doesNotContain("mediaType");
    }

    @Test
    void convertMessages_imageUrlBlock_serialisedAsAnthropicImageUrlContentBlock() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest.Message msg = LlmChatRequest.Message.imageUrl(
                "user", "https://example.com/cat.jpg", "Describe this.");

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(msg))
                .build();

        AnthropicRequest out = buildRequest(provider, req);
        @SuppressWarnings("unchecked")
        List<AnthropicRequest.ImageContentBlock> blocks =
                (List<AnthropicRequest.ImageContentBlock>) out.getMessages().get(0).getContent();
        assertThat(blocks).hasSize(2);

        AnthropicRequest.ImageContentBlock imgBlock = blocks.get(0);
        assertThat(imgBlock.getType()).isEqualTo("image");
        assertThat(imgBlock.getSource().getType()).isEqualTo("url");
        assertThat(imgBlock.getSource().getUrl()).isEqualTo("https://example.com/cat.jpg");
        // base64 fields must remain null on URL-source images
        assertThat(imgBlock.getSource().getMediaType()).isNull();
        assertThat(imgBlock.getSource().getData()).isNull();

        // JSON wire check — media_type / data omitted via JsonInclude(NON_NULL)
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(out);
        assertThat(json).contains("\"url\":\"https://example.com/cat.jpg\"");
        assertThat(json).doesNotContain("media_type");
        assertThat(json).doesNotContain("\"data\":");
    }

    @Test
    void convertMessages_mixedTextAndImage_preservesOrder() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest.Message msg = LlmChatRequest.Message.imageBase64(
                "user", "image/jpeg", SAMPLE_BASE64, "trailing text after image");

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(msg))
                .build();

        AnthropicRequest out = buildRequest(provider, req);
        @SuppressWarnings("unchecked")
        List<AnthropicRequest.ImageContentBlock> blocks =
                (List<AnthropicRequest.ImageContentBlock>) out.getMessages().get(0).getContent();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).getType()).isEqualTo("image");
        assertThat(blocks.get(1).getType()).isEqualTo("text");
        assertThat(blocks.get(1).getText()).isEqualTo("trailing text after image");
    }

    @Test
    void convertMessages_imageOnlyWithoutText_emitsSingleBlock() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest.Message msg = LlmChatRequest.Message.imageBase64(
                "user", "image/webp", SAMPLE_BASE64, null);

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(msg))
                .build();

        AnthropicRequest out = buildRequest(provider, req);
        @SuppressWarnings("unchecked")
        List<AnthropicRequest.ImageContentBlock> blocks =
                (List<AnthropicRequest.ImageContentBlock>) out.getMessages().get(0).getContent();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getType()).isEqualTo("image");
    }

    // =========================================================================
    // backward compatibility — text-only messages stay String, not List
    // =========================================================================

    @Test
    void convertMessages_textOnlyMessage_passesContentThroughAsString() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.text("user", "hello")))
                .build();

        AnthropicRequest out = buildRequest(provider, req);
        Object content = out.getMessages().get(0).getContent();
        // No conversion when content is a plain String
        assertThat(content).isEqualTo("hello");
    }

    // =========================================================================
    // capability gate — chat() entry-point throws on non-vision models
    // =========================================================================

    @Test
    void chat_visionRequiredButOldModel_throwsIllegalArgumentException() {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-2.1")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.imageBase64(
                        "user", "image/png", SAMPLE_BASE64, "describe")))
                .build();

        assertThatThrownBy(() -> provider.chat(req, "fake-key", "https://api.anthropic.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claude-2.1")
                .hasMessageContaining("does not support vision");
    }

    @Test
    void chat_visionRequiredAndModelCapable_doesNotThrowFromGate() throws Exception {
        // Note: the actual HTTP call would fail (null WebClient + fake key) but
        // the capability gate must NOT throw IllegalArgumentException. Any
        // exception we observe must come from the WebClient / NPE path, not
        // from our own gate — which is what we're asserting here.
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.imageBase64(
                        "user", "image/png", SAMPLE_BASE64, "describe")))
                .build();

        assertThatThrownBy(() -> provider.chat(req, "fake-key", "https://api.anthropic.com"))
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chat_textOnlyOnNonVisionModel_doesNotTriggerGate() throws Exception {
        // Sanity: the gate must only fire when image content is actually
        // present. Plain-text messages on legacy models stay viable.
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-2.1")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.text("user", "hello")))
                .build();

        assertThatThrownBy(() -> provider.chat(req, "fake-key", "https://api.anthropic.com"))
                .isNotInstanceOf(IllegalArgumentException.class);
    }
}
