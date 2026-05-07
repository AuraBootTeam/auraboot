package com.auraboot.framework.integration.automation;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.impl.LlmCallExecutor;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E.2 — Workflow {@code action-llm-call} vision integration tests.
 *
 * <p>Boots the real Spring context (BaseIntegrationTest = real PG + Redis) so
 * the executor's bean wiring, MetaContext-aware tenant resolution, and
 * provider-factory contract are exercised end-to-end. {@link LlmProvider}
 * itself is the only stubbed boundary — we capture the outgoing
 * {@link LlmChatRequest} via Mockito and assert the multimodal payload
 * shape.
 *
 * <p>Image-variable wire format (E.2 contract):
 * each image variable in the workflow context resolves to a String of the
 * form {@code data:image/<png|jpeg|gif|webp>;base64,<base64-bytes>}. Anything
 * else throws — no silent drop, no best-effort fallback.
 */
@DisplayName("LLM_CALL action — vision input (E.2)")
class LlmCallExecutorVisionIntegrationTest extends BaseIntegrationTest {

    /** Tiny 1x1 PNG, base64-encoded. Plenty for assertion-shape testing. */
    private static final String TINY_PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEA9G2MmQAAAABJRU5ErkJggg==";

    private static final String DATA_URI_PNG = "data:image/png;base64," + TINY_PNG_B64;
    private static final String DATA_URI_JPEG = "data:image/jpeg;base64," + TINY_PNG_B64;
    private static final String DATA_URI_GIF = "data:image/gif;base64," + TINY_PNG_B64;

    @Autowired
    private LlmCallExecutor executor;

    @MockitoBean
    private LlmProviderFactory llmProviderFactory;

    /**
     * Stub provider — recorded in tests via ArgumentCaptor. Not @MockitoBean
     * because the real provider beans (Anthropic / OpenAI-compat) stay wired
     * for other tests; we only mock the factory's getProvider() return.
     */
    private LlmProvider stubProvider() {
        return org.mockito.Mockito.mock(LlmProvider.class);
    }

    private void wireFactory(String providerCode, LlmProvider provider) {
        when(llmProviderFactory.resolveProviderByModel(anyString())).thenReturn(providerCode);
        when(llmProviderFactory.resolveConfig(any(), anyString()))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode(providerCode)
                        .apiKey("sk-test")
                        .baseUrl("https://api.example.com")
                        .defaultModel("claude-sonnet-4-6")
                        .maxTokens(4096)
                        .build());
        when(llmProviderFactory.getProvider(providerCode)).thenReturn(provider);
    }

    private LlmChatResponse textResponse(String text) {
        return LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text").text(text).build()))
                .inputTokens(50)
                .outputTokens(20)
                .build();
    }

    private AutomationAction llmAction(Map<String, Object> config) {
        return AutomationAction.builder()
                .type("llm_call")
                .config(new HashMap<>(config))
                .build();
    }

    // =========================================================================
    // Case A — single image variable produces text + image content blocks
    // =========================================================================

    @Test
    @DisplayName("Case A: 1 image var → outgoing message carries 1 image + 1 text block")
    void caseA_singleImageVar_buildsMultimodalRequest() throws Exception {
        LlmProvider provider = stubProvider();
        wireFactory("anthropic", provider);
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(textResponse("a cat"));

        AutomationAction action = llmAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "Describe this image briefly.",
                "imageVariableNames", List.of("screenshot")));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("screenshot", DATA_URI_PNG);

        Object result = executor.execute(action, ctx);

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(captor.capture(), anyString(), anyString());
        LlmChatRequest sent = captor.getValue();

        assertThat(sent.getMessages()).hasSize(1);
        LlmChatRequest.Message msg = sent.getMessages().get(0);
        assertThat(msg.getRole()).isEqualTo("user");
        assertThat(msg.getContent()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<LlmChatRequest.MessageContentBlock> blocks =
                (List<LlmChatRequest.MessageContentBlock>) msg.getContent();
        assertThat(blocks).hasSize(2);
        // Image first (Anthropic recommendation)
        assertThat(blocks.get(0).getType()).isEqualTo("image");
        assertThat(blocks.get(0).getSource()).isNotNull();
        assertThat(blocks.get(0).getSource().getType()).isEqualTo("base64");
        assertThat(blocks.get(0).getSource().getMediaType()).isEqualTo("image/png");
        assertThat(blocks.get(0).getSource().getData()).isEqualTo(TINY_PNG_B64);
        // Text last
        assertThat(blocks.get(1).getType()).isEqualTo("text");
        assertThat(blocks.get(1).getText()).isEqualTo("Describe this image briefly.");

        // Output bound under default key
        assertThat(ctx.get("llmOutput")).isEqualTo("a cat");
        assertThat(((Map<?, ?>) result).get("success")).isEqualTo(Boolean.TRUE);
    }

    // =========================================================================
    // Case B — three image vars retain configured order, prompt block last
    // =========================================================================

    @Test
    @DisplayName("Case B: 3 image vars → 3 image blocks in configured order + 1 text block")
    void caseB_multipleImageVars_orderPreserved() throws Exception {
        LlmProvider provider = stubProvider();
        wireFactory("anthropic", provider);
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(textResponse("ok"));

        AutomationAction action = llmAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "Compare these screenshots.",
                "imageVariableNames", List.of("before", "during", "after")));

        // LinkedHashMap to keep deterministic insertion order — but executor
        // must respect imageVariableNames order regardless of context order.
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("after", DATA_URI_GIF);     // intentionally inserted out-of-order
        ctx.put("during", DATA_URI_JPEG);
        ctx.put("before", DATA_URI_PNG);

        executor.execute(action, ctx);

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(captor.capture(), anyString(), anyString());
        @SuppressWarnings("unchecked")
        List<LlmChatRequest.MessageContentBlock> blocks =
                (List<LlmChatRequest.MessageContentBlock>) captor.getValue().getMessages().get(0).getContent();

        assertThat(blocks).hasSize(4);
        // Order: configured imageVariableNames → before, during, after, then text
        assertThat(blocks.get(0).getType()).isEqualTo("image");
        assertThat(blocks.get(0).getSource().getMediaType()).isEqualTo("image/png");   // before
        assertThat(blocks.get(1).getSource().getMediaType()).isEqualTo("image/jpeg"); // during
        assertThat(blocks.get(2).getSource().getMediaType()).isEqualTo("image/gif");  // after
        assertThat(blocks.get(3).getType()).isEqualTo("text");
        assertThat(blocks.get(3).getText()).isEqualTo("Compare these screenshots.");
    }

    // =========================================================================
    // Case C — image var name configured but missing in context → throw
    // =========================================================================

    @Test
    @DisplayName("Case C: image var missing in context → IllegalArgumentException, no provider call")
    void caseC_imageVarMissing_throwsAndDoesNotCallProvider() throws Exception {
        LlmProvider provider = stubProvider();
        wireFactory("anthropic", provider);

        AutomationAction action = llmAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "describe",
                "imageVariableNames", List.of("missingScreenshot")));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("trigger.text", "irrelevant");

        assertThatThrownBy(() -> executor.execute(action, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missingScreenshot")
                .hasMessageContaining("not found in trigger context");

        verify(provider, never()).chat(any(), anyString(), anyString());
    }

    // =========================================================================
    // Case D — non-vision provider with images configured → throw at executor
    // =========================================================================

    @Test
    @DisplayName("Case D: provider=openai-compatible + image vars → IllegalArgumentException")
    void caseD_nonVisionProvider_rejected() throws Exception {
        LlmProvider provider = stubProvider();
        wireFactory("openai-compatible", provider);

        AutomationAction action = llmAction(Map.of(
                "model", "deepseek-chat",
                "userPromptTemplate", "describe",
                "imageVariableNames", List.of("screenshot")));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("screenshot", DATA_URI_PNG);

        assertThatThrownBy(() -> executor.execute(action, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai-compatible")
                .hasMessageContaining("does not support vision");

        verify(provider, never()).chat(any(), anyString(), anyString());
    }

    // =========================================================================
    // Case E — image var resolves to invalid format → throw (not strip)
    // =========================================================================

    @Test
    @DisplayName("Case E.1: image var value is not data: URI → IllegalArgumentException")
    void caseE1_imageVarInvalidShape_throws() throws Exception {
        LlmProvider provider = stubProvider();
        wireFactory("anthropic", provider);

        AutomationAction action = llmAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "describe",
                "imageVariableNames", List.of("screenshot")));

        Map<String, Object> ctx = new HashMap<>();
        // Plain base64 without the data:image/...;base64, prefix
        ctx.put("screenshot", TINY_PNG_B64);

        assertThatThrownBy(() -> executor.execute(action, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid data URI");

        verify(provider, never()).chat(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Case E.2: image var has unsupported media type (image/bmp) → IllegalArgumentException")
    void caseE2_unsupportedMediaType_throws() throws Exception {
        LlmProvider provider = stubProvider();
        wireFactory("anthropic", provider);

        AutomationAction action = llmAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "describe",
                "imageVariableNames", List.of("screenshot")));

        Map<String, Object> ctx = new HashMap<>();
        // bmp is rejected even though it is a valid image MIME — Anthropic
        // doesn't accept it. Pre-check refuses upfront for clearer errors.
        // Note: regex pattern actually rejects image/bmp at the regex stage
        // because the MIME alternation is whitelisted. Either way → throws.
        ctx.put("screenshot", "data:image/bmp;base64," + TINY_PNG_B64);

        assertThatThrownBy(() -> executor.execute(action, ctx))
                .isInstanceOf(IllegalArgumentException.class);

        verify(provider, never()).chat(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Case E.3: image var value is not a String → IllegalArgumentException")
    void caseE3_imageVarNonString_throws() throws Exception {
        LlmProvider provider = stubProvider();
        wireFactory("anthropic", provider);

        AutomationAction action = llmAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "describe",
                "imageVariableNames", List.of("screenshot")));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("screenshot", 12345);  // numeric — not a base64 image

        assertThatThrownBy(() -> executor.execute(action, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a non-blank String");

        verify(provider, never()).chat(any(), anyString(), anyString());
    }

    // =========================================================================
    // Regression — text-only path unchanged when imageVariableNames absent
    // =========================================================================

    @Test
    @DisplayName("Regression: no imageVariableNames → legacy String content path preserved")
    void regression_textOnlyPathUnchanged() throws Exception {
        LlmProvider provider = stubProvider();
        wireFactory("anthropic", provider);
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(textResponse("done"));

        AutomationAction action = llmAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "summarise: ${trigger.text}"));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("trigger.text", "hello");

        executor.execute(action, ctx);

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(captor.capture(), anyString(), anyString());
        LlmChatRequest.Message msg = captor.getValue().getMessages().get(0);

        // Legacy contract: content is a String, not a list of blocks.
        assertThat(msg.getContent()).isInstanceOf(String.class);
        assertThat(msg.getContent()).isEqualTo("summarise: hello");
    }

    // =========================================================================
    // Image var key is NOT interpolated as text into prompt body
    // =========================================================================

    @Test
    @DisplayName("Image var keys are NOT substituted into prompt body — they flow as image blocks only")
    void imageVarKeyNotInterpolatedIntoPromptText() throws Exception {
        LlmProvider provider = stubProvider();
        wireFactory("anthropic", provider);
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(textResponse("ok"));

        AutomationAction action = llmAction(Map.of(
                "model", "claude-sonnet-4-6",
                // Author erroneously references the image var in text — must
                // be left as a literal placeholder, NOT replaced with the
                // base64 payload (which would corrupt the prompt and cost
                // tokens).
                "userPromptTemplate", "Look at ${screenshot} and describe.",
                "imageVariableNames", List.of("screenshot")));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("screenshot", DATA_URI_PNG);

        executor.execute(action, ctx);

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(captor.capture(), anyString(), anyString());
        @SuppressWarnings("unchecked")
        List<LlmChatRequest.MessageContentBlock> blocks =
                (List<LlmChatRequest.MessageContentBlock>) captor.getValue().getMessages().get(0).getContent();

        // The text block keeps the literal placeholder — no base64 inlining.
        LlmChatRequest.MessageContentBlock textBlock = blocks.stream()
                .filter(b -> "text".equals(b.getType())).findFirst().orElseThrow();
        assertThat(textBlock.getText()).isEqualTo("Look at ${screenshot} and describe.");
        assertThat(textBlock.getText()).doesNotContain(TINY_PNG_B64);
    }
}
