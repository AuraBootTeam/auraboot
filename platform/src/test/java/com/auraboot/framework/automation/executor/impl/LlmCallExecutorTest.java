package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.event.AutomationRunStreamPublisher;
import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LlmCallExecutor} (P1 — workflow LLM action node).
 *
 * <p>Mirrors the existing executor unit test style (Mockito-based) — the SDK
 * itself contains higher-level integration tests at {@code AnthropicLlmProviderIntegrationTest}.
 * Here we mock {@link LlmProviderFactory} + {@link LlmProvider} so we can
 * assert on the wire request shape and on capability gating without standing
 * up the WebClient.
 */
@ExtendWith(MockitoExtension.class)
class LlmCallExecutorTest {

    @Mock
    private LlmProviderFactory llmProviderFactory;

    /**
     * Use {@link Answers#CALLS_REAL_METHODS} so the default {@code streamChat}
     * implementation on {@link LlmProvider} runs and forwards to the
     * Mockito-stubbed sync {@code chat} method. Without this, the executor's
     * E.1 switch from {@code chat()} to {@code streamChat()} would break
     * every existing assertion that relies on {@code when(provider.chat(...))}.
     */
    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private LlmProvider llmProvider;

    /**
     * Stream publisher is wired but unused by these unit tests (chunks fire
     * into a no-op sink). Existence ensures Mockito can satisfy the
     * {@link LlmCallExecutor} constructor's two-arg signature.
     */
    @Mock
    private AutomationRunStreamPublisher streamPublisher;

    @InjectMocks
    private LlmCallExecutor executor;

    @BeforeEach
    void wireProviderResolution() {
        // Default happy-path resolution. Individual tests override as needed
        // before calling execute(). Kept lenient so tests that never trigger
        // resolution (validation failures) don't trip "unnecessary stubbing".
        lenient().when(llmProviderFactory.resolveProviderByModel(anyString())).thenReturn("anthropic");
        lenient().when(llmProviderFactory.resolveConfig(any(), anyString()))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("anthropic")
                        .apiKey("sk-test")
                        .baseUrl("https://api.anthropic.com")
                        .defaultModel("claude-sonnet-4-6")
                        .maxTokens(4096)
                        .build());
        lenient().when(llmProviderFactory.getProvider("anthropic")).thenReturn(llmProvider);
    }

    // =========================================================
    // supports()
    // =========================================================

    @Test
    void supports_llmCall_returnsTrue() {
        assertThat(executor.supports("llm_call")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(executor.supports("call_api")).isFalse();
        assertThat(executor.supports("create_record")).isFalse();
        assertThat(executor.supports(null)).isFalse();
    }

    // =========================================================
    // execute() — config validation
    // =========================================================

    @Test
    void execute_nullConfig_throwsIllegalArgument() {
        AutomationAction action = AutomationAction.builder().type("llm_call").config(null).build();

        assertThatThrownBy(() -> executor.execute(action, new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
    }

    @Test
    void execute_missingUserPromptTemplate_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of("model", "claude-sonnet-4-6"));

        assertThatThrownBy(() -> executor.execute(action, new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userPromptTemplate");
    }

    @Test
    void execute_blankUserPromptTemplate_throwsIllegalArgument() {
        AutomationAction action = buildAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "   "));

        assertThatThrownBy(() -> executor.execute(action, new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userPromptTemplate");
    }

    // =========================================================
    // execute() — happy path: template interpolation + output binding
    // =========================================================

    @Test
    void executes_basic_template_with_variable_interpolation() throws Exception {
        when(llmProvider.chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://api.anthropic.com")))
                .thenReturn(buildTextResponse("summary"));

        AutomationAction action = buildAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "Summarise: ${trigger.text}",
                "maxTokens", 256
        ));
        Map<String, Object> context = new HashMap<>();
        context.put("trigger.text", "hello world");

        Object result = executor.execute(action, context);

        // Assertion 1: response stored under default outputVariableName
        assertThat(context.get("llmOutput")).isEqualTo("summary");

        // Assertion 2: returned result map carries provider/model/output
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(resultMap.get("model")).isEqualTo("claude-sonnet-4-6");
        assertThat(resultMap.get("providerCode")).isEqualTo("anthropic");
        assertThat(resultMap.get("output")).isEqualTo("summary");
        assertThat(resultMap.get("outputVariable")).isEqualTo("llmOutput");

        // Assertion 3: ${trigger.text} got interpolated before reaching provider
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmProvider).chat(captor.capture(), anyString(), anyString());
        LlmChatRequest sent = captor.getValue();
        assertThat(sent.getMessages()).hasSize(1);
        assertThat(sent.getMessages().get(0).getRole()).isEqualTo("user");
        assertThat(sent.getMessages().get(0).getContent()).isEqualTo("Summarise: hello world");
        assertThat(sent.getMaxTokens()).isEqualTo(256);
        assertThat(sent.getThinking()).isNull();
    }

    @Test
    void executes_with_custom_output_variable_name() throws Exception {
        when(llmProvider.chat(any(), anyString(), anyString()))
                .thenReturn(buildTextResponse("classified-as-bug"));

        AutomationAction action = buildAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "classify",
                "outputVariableName", "ticketCategory"
        ));
        Map<String, Object> context = new HashMap<>();

        executor.execute(action, context);

        assertThat(context.get("ticketCategory")).isEqualTo("classified-as-bug");
        assertThat(context).doesNotContainKey("llmOutput");
    }

    // =========================================================
    // execute() — Extended Thinking propagation
    // =========================================================

    @Test
    void executes_with_thinking_enabled_propagatesToProvider() throws Exception {
        when(llmProvider.chat(any(), anyString(), anyString()))
                .thenReturn(buildTextResponse("ok"));

        AutomationAction action = buildAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "deep think please",
                "thinkingEnabled", true,
                "thinkingBudgetTokens", 5000
        ));

        executor.execute(action, new HashMap<>());

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmProvider).chat(captor.capture(), anyString(), anyString());
        LlmChatRequest sent = captor.getValue();

        assertThat(sent.getThinking()).isNotNull();
        assertThat(sent.getThinking().isEnabled()).isTrue();
        assertThat(sent.getThinking().getBudgetTokens()).isEqualTo(5000);
    }

    // =========================================================
    // execute() — capability gating
    // =========================================================

    @Test
    void unsupported_model_for_thinking_throws() {
        // claude-3-haiku-20240307 is a legacy model; thinking must NOT be
        // silently dropped — workflow author opted in explicitly.
        AutomationAction action = buildAction(Map.of(
                "model", "claude-3-haiku-20240307",
                "userPromptTemplate", "x",
                "thinkingEnabled", true
        ));

        assertThatThrownBy(() -> executor.execute(action, new HashMap<>()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not support Extended Thinking");
    }

    // =========================================================
    // execute() — failure semantics
    // =========================================================

    @Test
    void failure_returns_node_status_failed_without_workflow_abort() throws Exception {
        // The trigger service catches exceptions thrown from execute() and
        // converts them to ActionResult.status=FAILED. Our contract is to
        // throw BusinessException with a useful message, NOT to swallow.
        when(llmProvider.chat(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("upstream 503"));

        AutomationAction action = buildAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "x"
        ));

        assertThatThrownBy(() -> executor.execute(action, new HashMap<>()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("LLM_CALL failed")
                .hasMessageContaining("upstream 503");
    }

    @Test
    void no_provider_configured_throws_business_exception() {
        when(llmProviderFactory.resolveConfig(any(), anyString())).thenReturn(null);

        AutomationAction action = buildAction(Map.of(
                "model", "claude-sonnet-4-6",
                "userPromptTemplate", "x"
        ));

        assertThatThrownBy(() -> executor.execute(action, new HashMap<>()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no LLM provider configured");
    }

    // =========================================================
    // Helpers
    // =========================================================

    private AutomationAction buildAction(Map<String, Object> config) {
        return AutomationAction.builder()
                .type("llm_call")
                .config(new HashMap<>(config))
                .build();
    }

    private LlmChatResponse buildTextResponse(String text) {
        return LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text")
                        .text(text)
                        .build()))
                .inputTokens(10)
                .outputTokens(5)
                .build();
    }
}
