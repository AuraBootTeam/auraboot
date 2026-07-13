package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vision on the OpenAI-compatible provider.
 *
 * <p>This provider used to refuse <i>all</i> image input, because the models behind the same
 * endpoint disagree about vision and one comment here claimed qwen-vl needed a bespoke field shape.
 * That last part turned out to be false: it is true of DashScope's <i>native</i> API, not of its
 * OpenAI-compatible one, which takes standard {@code image_url} blocks (verified live). So the
 * blanket refusal is replaced by a per-model capability gate.
 *
 * <p>Two things have to be right, and neither is visible in the type signatures:
 *
 * <ol>
 *   <li><b>The gate.</b> A blind model handed an image does not fail — it answers from the prompt
 *       alone, just as confidently, and the caller cannot tell the picture was ignored. An unlisted
 *       model must therefore be refused outright.</li>
 *   <li><b>The wire shape.</b> The internal DTO is Anthropic-shaped
 *       ({@code {type:image, source:{base64}}}); OpenAI wants
 *       {@code {type:image_url, image_url:{url:"data:..."}}}. Untranslated, the block falls through
 *       to {@code String.valueOf(content)} and the model is posted a Java toString.</li>
 * </ol>
 */
@DisplayName("OpenAiCompatibleLlmProvider — vision")
class OpenAiCompatibleLlmProviderVisionTest {

    private static final String SAMPLE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

    private final OpenAiCompatibleLlmProvider provider =
            new OpenAiCompatibleLlmProvider(null, new ObjectMapper());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmChatRequest imageRequest(String model) {
        return LlmChatRequest.builder()
                .model(model)
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.imageBase64(
                        "user", "image/png", SAMPLE_BASE64, "what is this?")))
                .build();
    }

    // -------------------------------------------------------------------------
    // Capability gate
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "qwen-vl-max",
            "qwen-vl-max-0809",        // a dated release — matched as a substring
            "qwen2.5-vl-72b-instruct",
            "gpt-4o",
            "gpt-4o-2024-08-06",
            "glm-4v",
    })
    @DisplayName("models that can see are accepted")
    void acceptsVisionModels(String model) {
        assertThat(provider.supportsVision(model)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "deepseek-chat",
            "qwen-plus",   // same endpoint as qwen-vl, but blind
            "moonshot-v1-8k",
            "glm-4",       // one character away from glm-4v
    })
    @DisplayName("models that cannot see are not accepted, however close their names look")
    void rejectsTextOnlyModels(String model) {
        assertThat(provider.supportsVision(model)).isFalse();
    }

    @Test
    @DisplayName("an image sent to a blind model is refused, not silently dropped")
    void refusesImageOnTextOnlyModel() {
        assertThatThrownBy(() -> provider.chat(imageRequest("deepseek-chat"), "fake-key", "https://x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not accept image input");
    }

    @Test
    @DisplayName("the vision gate does not fire on text-only requests")
    void textOnlyRequestDoesNotTriggerTheGate() {
        LlmChatRequest req = LlmChatRequest.builder()
                .model("gpt-4o")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.text("user", "hello")))
                .build();

        // The failure here comes from the null WebClient, not from our guard.
        assertThatThrownBy(() -> provider.chat(req, "fake-key", "https://api.openai.com"))
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Wire shape
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("inline image bytes become an OpenAI data: URI, alongside the prompt text")
    void translatesBase64ImageToOpenAiShape() throws Exception {
        Map<String, Object> body = provider.buildOpenAiRequestBody(imageRequest("qwen-vl-max"));

        List<Map<String, Object>> parts = contentPartsOf(body);
        Map<String, Object> image = partOfType(parts, "image_url");

        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) image.get("image_url");
        assertThat((String) imageUrl.get("url"))
                .startsWith("data:image/png;base64,")
                .endsWith(SAMPLE_BASE64);

        assertThat(partOfType(parts, "text").get("text")).isEqualTo("what is this?");
    }

    @Test
    @DisplayName("a remote image URL is passed through as-is")
    void passesRemoteImageUrlThrough() throws Exception {
        LlmChatRequest req = LlmChatRequest.builder()
                .model("gpt-4o")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.imageUrl(
                        "user", "https://example.com/img.jpg", "describe")))
                .build();

        List<Map<String, Object>> parts = contentPartsOf(provider.buildOpenAiRequestBody(req));

        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) partOfType(parts, "image_url").get("image_url");
        assertThat(imageUrl.get("url")).isEqualTo("https://example.com/img.jpg");
    }

    @Test
    @DisplayName("the serialized body is real JSON — no Java toString leaks onto the wire")
    void bodySerializesCleanly() throws Exception {
        String json = objectMapper.writeValueAsString(
                provider.buildOpenAiRequestBody(imageRequest("qwen-vl-max")));

        assertThat(json).contains("\"image_url\"", "data:image/png;base64,");
        // What used to reach the wire when the block was stringified.
        assertThat(json).doesNotContain("MessageContentBlock", "ImageSource");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contentPartsOf(Map<String, Object> body) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertThat(messages).hasSize(1);

        Object content = messages.get(0).get("content");
        assertThat(content)
                .as("content must be an OpenAI content array, not a stringified Java object")
                .isInstanceOf(List.class);
        return (List<Map<String, Object>>) content;
    }

    private Map<String, Object> partOfType(List<Map<String, Object>> parts, String type) {
        return parts.stream()
                .filter(p -> type.equals(p.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " block in " + parts));
    }
}
