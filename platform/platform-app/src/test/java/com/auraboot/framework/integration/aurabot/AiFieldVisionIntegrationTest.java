package com.auraboot.framework.integration.aurabot;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.AnthropicLlmProvider;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.OpenAiCompatibleLlmProvider;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.ai.AiFieldProcessor;
import com.auraboot.framework.meta.ai.AiFieldProcessor.AiGenerationRequest;
import com.auraboot.framework.meta.ai.AiFieldProcessor.AiGenerationResult;
import com.auraboot.framework.meta.ai.AiFieldProcessor.ImageInput;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F.3 — vision input on the generic form AI field.
 *
 * <p>Verifies the multi-modal codepath introduced in {@link AiFieldProcessor}:
 * <ul>
 *   <li>{@code images=[base64...]} produces a {@link LlmChatRequest.Message}
 *       whose content carries an {@code image} block (not plain-text).</li>
 *   <li>{@code images=[]} / {@code null} keeps the legacy text-only message
 *       shape — no regression on the F.3-untouched callers.</li>
 *   <li>Non-Anthropic providers reject vision explicitly with
 *       {@link IllegalArgumentException} — surfaced as an error in the
 *       {@link AiGenerationResult} rather than silently dropped.</li>
 * </ul>
 *
 * <p>We mock {@link LlmProviderFactory} (not the underlying HTTP) so the test
 * is hermetic and runs even when {@code ai.service.enabled=false} in the
 * shared integration-test profile (we override it below).
 */
@Slf4j
@DisplayName("AiField vision (F.3) - Integration Tests")
@TestPropertySource(properties = "ai.service.enabled=true")
class AiFieldVisionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AiFieldProcessor aiFieldProcessor;

    @Autowired
    private OpenAiCompatibleLlmProvider realOpenAiCompatProvider;

    @MockitoBean
    private LlmProviderFactory llmProviderFactory;

    /** Tiny fake base64 — content doesn't matter, structure does. */
    private static final String FAKE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

    private LlmProvider mockProvider;

    @BeforeEach
    void setupProviderConfig() {
        // Stub config returned by factory (anthropic by default — overridden
        // per-test for the rejection case).
        LlmProviderFactory.ProviderConfig anthropicConfig = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("test-key")
                .baseUrl("https://example.test")
                .defaultModel("claude-sonnet-4-6")
                .build();
        when(llmProviderFactory.resolveConfig(any(), any())).thenReturn(anthropicConfig);

        mockProvider = mock(AnthropicLlmProvider.class);
        when(llmProviderFactory.getProvider("anthropic")).thenReturn(mockProvider);

        // Default: provider returns a successful text response.
        LlmChatResponse okResponse = LlmChatResponse.builder()
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text").text("ocr-extracted text").build()))
                .inputTokens(10).outputTokens(5)
                .build();
        try {
            when(mockProvider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                    .thenReturn(okResponse);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // The processor caches @Value boolean at field-init; @TestPropertySource
        // alone may not flip it after Spring context cached the bean elsewhere.
        // Force-set the flag via reflection so this test is self-contained.
        ReflectionTestUtils.setField(aiFieldProcessor, "aiEnabled", true);
    }

    @Test
    @DisplayName("images=[base64] → captured LlmChatRequest carries image content block")
    void visionPath_buildsImageBlock() throws Exception {
        ImageInput image = ImageInput.builder()
                .mediaType("image/png")
                .data(FAKE_BASE64)
                .build();
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("extract")
                .prompt("Extract receipt total")
                .extractFields(List.of("total", "date"))
                .images(List.of(image))
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("ocr-extracted text");

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(mockProvider).chat(captor.capture(), anyString(), anyString());
        LlmChatRequest captured = captor.getValue();

        assertThat(captured.getMessages()).hasSize(1);
        Object content = captured.getMessages().get(0).getContent();
        assertThat(content)
                .as("content must be a list of MessageContentBlocks (multimodal), not a plain String")
                .isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<LlmChatRequest.MessageContentBlock> blocks =
                (List<LlmChatRequest.MessageContentBlock>) content;
        // Layout: [image, text] per Message.imageBase64 contract.
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).getType()).isEqualTo("image");
        assertThat(blocks.get(0).getSource()).isNotNull();
        assertThat(blocks.get(0).getSource().getType()).isEqualTo("base64");
        assertThat(blocks.get(0).getSource().getMediaType()).isEqualTo("image/png");
        assertThat(blocks.get(0).getSource().getData()).isEqualTo(FAKE_BASE64);
        assertThat(blocks.get(1).getType()).isEqualTo("text");
        assertThat(blocks.get(1).getText()).contains("Extract");
    }

    @Test
    @DisplayName("images=[] → text-only message (no regression)")
    void textOnlyPath_unchanged() throws Exception {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("generate")
                .prompt("Hello world")
                .images(List.of())
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(mockProvider).chat(captor.capture(), anyString(), anyString());
        LlmChatRequest captured = captor.getValue();

        assertThat(captured.getMessages()).hasSize(1);
        Object content = captured.getMessages().get(0).getContent();
        assertThat(content)
                .as("text-only path must produce a plain String content, not multimodal blocks")
                .isInstanceOf(String.class);
        assertThat((String) content).contains("Hello world");
    }

    @Test
    @DisplayName("images=null → text-only message (default back-compat)")
    void nullImages_textOnly() throws Exception {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("summarize")
                .sourceContent(Map.of("title", "demo"))
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(mockProvider).chat(captor.capture(), anyString(), anyString());
        assertThat(captor.getValue().getMessages().get(0).getContent()).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("non-vision provider (openai-compat) rejects images explicitly")
    void nonVisionProvider_rejectsExplicitly() throws Exception {
        // Switch the resolved provider config to a non-anthropic code.
        LlmProviderFactory.ProviderConfig deepseek = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("deepseek")
                .apiKey("k")
                .baseUrl("https://example.test")
                .defaultModel("deepseek-chat")
                .build();
        when(llmProviderFactory.resolveConfig(any(), any())).thenReturn(deepseek);

        // Use the real OpenAiCompatibleLlmProvider bean so the actual rejection
        // logic runs (it throws IllegalArgumentException on image content).
        when(llmProviderFactory.getProvider(eq("deepseek"))).thenReturn(realOpenAiCompatProvider);

        ImageInput image = ImageInput.builder()
                .mediaType("image/png").data(FAKE_BASE64).build();
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("extract")
                .prompt("Read invoice")
                .images(List.of(image))
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertFalse(result.isSuccess(), "non-vision provider must surface failure, not silently drop");
        assertThat(result.getError())
                .as("error message must be explicit about vision unsupported")
                .containsIgnoringCase("vision");

        // The anthropic mock must NOT have been invoked when provider was deepseek.
        verify(mockProvider, never()).chat(any(), anyString(), anyString());
    }
}
