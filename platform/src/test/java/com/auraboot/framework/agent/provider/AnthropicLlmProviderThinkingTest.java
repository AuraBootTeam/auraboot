package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.AnthropicRequest;
import com.auraboot.framework.agent.dto.AnthropicResponse;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AnthropicLlmProvider — Extended Thinking (P0-2) wire-up.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code thinking} block is included in the Anthropic request when the
 *       unified request enables it AND the model is capability-gated as a
 *       Sonnet 4.6+/Opus 4.x/Haiku 4.x family member.</li>
 *   <li>{@code thinking} is omitted when disabled (default null) — preserves
 *       backward compatibility with existing requests.</li>
 *   <li>{@code thinking} is suppressed for old models (claude-3-*) even if the
 *       caller asks for it — capability gate.</li>
 *   <li>The provider parses {@code {type:"thinking", thinking:"...", signature:"..."}}
 *       content blocks coming back from Anthropic into unified ContentBlocks.</li>
 *   <li>When the requested thinking budget would breach Anthropic's
 *       {@code max_tokens >= budget + 1024} constraint, max_tokens is auto-extended
 *       to {@code budget + 4096} on the wire to avoid HTTP 400.</li>
 * </ul>
 */
class AnthropicLlmProviderThinkingTest {

    private AnthropicLlmProvider createProvider() {
        return new AnthropicLlmProvider(null, new ObjectMapper());
    }

    private AnthropicRequest buildRequest(AnthropicLlmProvider provider, LlmChatRequest req) throws Exception {
        Method m = AnthropicLlmProvider.class.getDeclaredMethod("buildAnthropicRequest", LlmChatRequest.class);
        m.setAccessible(true);
        return (AnthropicRequest) m.invoke(provider, req);
    }

    private LlmChatResponse convertResponse(AnthropicLlmProvider provider, AnthropicResponse resp) throws Exception {
        Method m = AnthropicLlmProvider.class.getDeclaredMethod("convertResponse", AnthropicResponse.class);
        m.setAccessible(true);
        return (LlmChatResponse) m.invoke(provider, resp);
    }

    private boolean supportsThinking(AnthropicLlmProvider provider, String model) throws Exception {
        Method m = AnthropicLlmProvider.class.getDeclaredMethod("supportsThinking", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(provider, model);
    }

    // =========================================================================
    // capability gate: which model families support thinking
    // =========================================================================

    @Test
    void supportsThinking_acceptsSonnet46AndAbove() throws Exception {
        AnthropicLlmProvider provider = createProvider();
        assertThat(supportsThinking(provider, "claude-sonnet-4-6")).isTrue();
        assertThat(supportsThinking(provider, "claude-sonnet-4-7")).isTrue();
        assertThat(supportsThinking(provider, "claude-opus-4")).isTrue();
        assertThat(supportsThinking(provider, "claude-opus-4-7")).isTrue();
        assertThat(supportsThinking(provider, "claude-haiku-4")).isTrue();
        assertThat(supportsThinking(provider, "claude-haiku-4-5")).isTrue();
    }

    @Test
    void supportsThinking_rejectsLegacyClaude3Models() throws Exception {
        AnthropicLlmProvider provider = createProvider();
        assertThat(supportsThinking(provider, "claude-3-5-sonnet")).isFalse();
        assertThat(supportsThinking(provider, "claude-3-opus")).isFalse();
        assertThat(supportsThinking(provider, "claude-3-haiku")).isFalse();
        // Legacy Sonnet 3.5 must NOT match the family substring "sonnet-4-6"
        assertThat(supportsThinking(provider, "claude-3-5-sonnet-20241022")).isFalse();
    }

    @Test
    void supportsThinking_rejectsNullAndEmptyAndUnknown() throws Exception {
        AnthropicLlmProvider provider = createProvider();
        assertThat(supportsThinking(provider, null)).isFalse();
        assertThat(supportsThinking(provider, "")).isFalse();
        assertThat(supportsThinking(provider, "gpt-4o")).isFalse();
        assertThat(supportsThinking(provider, "deepseek-chat")).isFalse();
    }

    // =========================================================================
    // request build: thinking field propagation
    // =========================================================================

    @Test
    void chat_thinkingEnabled_includesThinkingFieldInRequest() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(20_000)
                .systemPrompt("You are a helpful assistant.")
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("Solve this puzzle.").build()))
                .thinking(LlmChatRequest.ThinkingConfig.builder()
                        .enabled(true).budgetTokens(8_000).build())
                .build();

        AnthropicRequest out = buildRequest(provider, req);

        assertThat(out.getThinking())
                .as("thinking block must be propagated to Anthropic request when enabled")
                .isNotNull();
        assertThat(out.getThinking().getType()).isEqualTo("enabled");
        assertThat(out.getThinking().getBudget_tokens()).isEqualTo(8_000);
    }

    @Test
    void chat_thinkingDisabled_omitsThinkingField() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        // Case A: thinking is null — most common path, must remain backward compatible
        LlmChatRequest reqNull = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("hi").build()))
                .build();
        assertThat(buildRequest(provider, reqNull).getThinking())
                .as("null ThinkingConfig must NOT add a thinking field")
                .isNull();

        // Case B: thinking present but enabled=false — caller may toggle dynamically
        LlmChatRequest reqDisabled = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("hi").build()))
                .thinking(LlmChatRequest.ThinkingConfig.builder().enabled(false).budgetTokens(8000).build())
                .build();
        assertThat(buildRequest(provider, reqDisabled).getThinking())
                .as("enabled=false must NOT add a thinking field")
                .isNull();
    }

    @Test
    void chat_oldModel_skipsThinkingEvenIfRequested() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-3-5-sonnet-20241022")
                .maxTokens(20_000)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("hi").build()))
                .thinking(LlmChatRequest.ThinkingConfig.builder().enabled(true).budgetTokens(10_000).build())
                .build();

        AnthropicRequest out = buildRequest(provider, req);

        assertThat(out.getThinking())
                .as("legacy claude-3 models must not receive thinking even if caller requests it")
                .isNull();
    }

    // =========================================================================
    // response parsing: thinking block content
    // =========================================================================

    @Test
    void chat_thinkingResponse_parsedIntoContentBlock() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        AnthropicResponse resp = new AnthropicResponse();
        resp.setStop_reason("end_turn");

        AnthropicResponse.ContentBlock thinkingBlock = new AnthropicResponse.ContentBlock();
        thinkingBlock.setType("thinking");
        thinkingBlock.setThinking("Step 1: parse the request. Step 2: choose a tool.");
        thinkingBlock.setSignature("EabcXYZ_signature_blob");

        AnthropicResponse.ContentBlock textBlock = new AnthropicResponse.ContentBlock();
        textBlock.setType("text");
        textBlock.setText("Here is the answer.");

        resp.setContent(List.of(thinkingBlock, textBlock));
        AnthropicResponse.Usage usage = new AnthropicResponse.Usage();
        usage.setInput_tokens(50);
        usage.setOutput_tokens(120);
        resp.setUsage(usage);

        LlmChatResponse out = convertResponse(provider, resp);

        assertThat(out.getContent()).hasSize(2);

        LlmChatResponse.ContentBlock first = out.getContent().get(0);
        assertThat(first.getType()).isEqualTo("thinking");
        assertThat(first.getThinking()).isEqualTo("Step 1: parse the request. Step 2: choose a tool.");
        assertThat(first.getSignature()).isEqualTo("EabcXYZ_signature_blob");

        LlmChatResponse.ContentBlock second = out.getContent().get(1);
        assertThat(second.getType()).isEqualTo("text");
        assertThat(second.getText()).isEqualTo("Here is the answer.");

        // Token counts still flow through unchanged
        assertThat(out.getInputTokens()).isEqualTo(50);
        assertThat(out.getOutputTokens()).isEqualTo(120);
    }

    // =========================================================================
    // max_tokens auto-extension when budget is too tight
    // =========================================================================

    @Test
    void chat_budgetExceedsMaxTokens_autoExtends() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        // budget=10_000, max_tokens=4096 — Anthropic requires max_tokens > budget_tokens
        // (and we want at least budget+1024 headroom); provider must auto-extend.
        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("solve").build()))
                .thinking(LlmChatRequest.ThinkingConfig.builder().enabled(true).budgetTokens(10_000).build())
                .build();

        AnthropicRequest out = buildRequest(provider, req);

        assertThat(out.getThinking()).isNotNull();
        assertThat(out.getThinking().getBudget_tokens()).isEqualTo(10_000);
        // budget(10_000) + 4096 fallback headroom = 14_096
        assertThat(out.getMax_tokens())
                .as("max_tokens must be auto-extended to budget + 4096 to avoid Anthropic 400")
                .isEqualTo(14_096);
    }

    @Test
    void chat_budgetWithinMaxTokens_keepsCallerMaxTokens() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(20_000)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("solve").build()))
                .thinking(LlmChatRequest.ThinkingConfig.builder().enabled(true).budgetTokens(10_000).build())
                .build();

        AnthropicRequest out = buildRequest(provider, req);
        assertThat(out.getMax_tokens())
                .as("caller's max_tokens must be preserved when it already accommodates the budget + 1024 headroom")
                .isEqualTo(20_000);
    }

    // =========================================================================
    // M9 — auto-extension is no longer a silent log-only fallback. The provider
    // must surface a warning string to the caller on LlmChatResponse.warnings
    // (or via the warningsOut overload of buildAnthropicRequest) so downstream
    // code can route the message instead of having it disappear into the log.
    // =========================================================================

    @Test
    void chat_budgetExceedsMaxTokens_emitsWarningInsteadOfSilentFallback() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("solve").build()))
                .thinking(LlmChatRequest.ThinkingConfig.builder().enabled(true).budgetTokens(10_000).build())
                .build();

        // Use the warnings-collecting overload — this is what production
        // chat() runs internally so we exercise the same code path.
        Method m = AnthropicLlmProvider.class.getDeclaredMethod(
                "buildAnthropicRequest", LlmChatRequest.class, java.util.List.class);
        m.setAccessible(true);
        java.util.List<String> warnings = new java.util.ArrayList<>();
        AnthropicRequest out = (AnthropicRequest) m.invoke(provider, req, warnings);

        assertThat(out.getMax_tokens()).isEqualTo(14_096);
        assertThat(warnings)
                .as("auto-extension must emit a single warning describing the budget mismatch; "
                        + "silent fallback violates the no-fallback red line")
                .hasSize(1);
        assertThat(warnings.get(0))
                .contains("budget", "10000")
                .contains("max_tokens", "4096")
                .contains("auto-extended", "14096");
    }

    @Test
    void chat_noAutoExtension_doesNotEmitWarning() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(20_000)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("solve").build()))
                .thinking(LlmChatRequest.ThinkingConfig.builder().enabled(true).budgetTokens(10_000).build())
                .build();

        Method m = AnthropicLlmProvider.class.getDeclaredMethod(
                "buildAnthropicRequest", LlmChatRequest.class, java.util.List.class);
        m.setAccessible(true);
        java.util.List<String> warnings = new java.util.ArrayList<>();
        m.invoke(provider, req, warnings);

        assertThat(warnings)
                .as("clean call must not emit any warning — only the auto-extension path should")
                .isEmpty();
    }

    // =========================================================================
    // JSON wire format: thinking block serialization (sanity check)
    // =========================================================================

    @Test
    void thinkingBlockSerializesAsSnakeCaseJson() throws Exception {
        AnthropicLlmProvider provider = createProvider();
        ObjectMapper mapper = new ObjectMapper();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(20_000)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("hi").build()))
                .thinking(LlmChatRequest.ThinkingConfig.builder().enabled(true).budgetTokens(8_000).build())
                .build();

        AnthropicRequest out = buildRequest(provider, req);
        String json = mapper.writeValueAsString(out);

        assertThat(json).contains("\"thinking\":{");
        assertThat(json).contains("\"type\":\"enabled\"");
        assertThat(json).contains("\"budget_tokens\":8000");
    }

    @Test
    void absentThinkingBlockIsOmittedFromJson() throws Exception {
        AnthropicLlmProvider provider = createProvider();
        ObjectMapper mapper = new ObjectMapper();

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .messages(List.of(LlmChatRequest.Message.builder().role("user").content("hi").build()))
                .build();

        AnthropicRequest out = buildRequest(provider, req);
        String json = mapper.writeValueAsString(out);

        // JsonInclude(NON_NULL) on AnthropicRequest must drop the absent field entirely
        assertThat(json).doesNotContain("\"thinking\"");
    }

    // =========================================================================
    // Map<String, Object> input is still passed through for non-thinking blocks
    // (regression guard — ensure existing tool_use parsing didn't break)
    // =========================================================================

    @Test
    void convertResponse_toolUseBlocks_stillCarryInputMap() throws Exception {
        AnthropicLlmProvider provider = createProvider();

        AnthropicResponse resp = new AnthropicResponse();
        AnthropicResponse.ContentBlock toolBlock = new AnthropicResponse.ContentBlock();
        toolBlock.setType("tool_use");
        toolBlock.setId("toolu_01");
        toolBlock.setName("nq__customers_active");
        toolBlock.setInput(Map.of("limit", 10));
        resp.setContent(List.of(toolBlock));
        resp.setStop_reason("tool_use");

        LlmChatResponse out = convertResponse(provider, resp);
        assertThat(out.getContent()).hasSize(1);
        assertThat(out.getContent().get(0).getType()).isEqualTo("tool_use");
        assertThat(out.getContent().get(0).getInput()).containsEntry("limit", 10);
        // Thinking fields are null for non-thinking blocks
        assertThat(out.getContent().get(0).getThinking()).isNull();
        assertThat(out.getContent().get(0).getSignature()).isNull();
    }
}
