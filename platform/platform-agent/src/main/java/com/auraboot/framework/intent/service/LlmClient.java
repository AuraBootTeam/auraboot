package com.auraboot.framework.intent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;

/**
 * Abstraction for LLM chat calls.
 * Allows easy mocking in integration tests.
 *
 * <p>The single SAM method is {@link #chat(String)} so the lighter-weight
 * tests (e.g. {@code IntentAnalyzerServiceTest}) can keep using lambdas like
 * {@code prompt -> MOCK_RESPONSE}. The 2-arg overload
 * {@link #chat(String, ChatOptions)} is a {@code default} that ignores the
 * options — production implementations override it to honour Extended
 * Thinking and other per-call knobs.
 */
@FunctionalInterface
public interface LlmClient {

    /**
     * Send a prompt to the LLM and return the text response.
     *
     * @param prompt the prompt text
     * @return the LLM response text
     */
    String chat(String prompt);

    /**
     * Send a prompt to the LLM with caller-specified options (Extended
     * Thinking, model override, max tokens override).
     *
     * <p>P0-2 follow-up: this overload exists so callers can actually enable
     * Anthropic Extended Thinking — the legacy {@link #chat(String)} path
     * cannot. Production implementations route this through
     * {@link com.auraboot.framework.agent.provider.LlmProvider}; the default
     * here simply ignores {@code options} so test doubles using the SAM
     * lambda form remain valid {@link FunctionalInterface}s.
     *
     * @param prompt  the prompt text
     * @param options request-level overrides; never {@code null}
     * @return the LLM response text
     */
    default String chat(String prompt, ChatOptions options) {
        return chat(prompt);
    }

    /**
     * Per-call overrides for {@link #chat(String, ChatOptions)}.
     *
     * <p>All fields are nullable; {@code null} means "use the client's
     * default behaviour". {@code thinking} is forwarded verbatim into
     * {@link LlmChatRequest#getThinking()} so capability gating stays in
     * the provider (legacy Claude 3 / OpenAI silently drop it).
     *
     * @param thinking          Extended Thinking knob; {@code null} disables
     * @param maxTokensOverride per-call max_tokens; {@code null} uses provider default
     * @param modelOverride     per-call model; {@code null} uses provider default
     */
    record ChatOptions(
            LlmChatRequest.ThinkingConfig thinking,
            Integer maxTokensOverride,
            String modelOverride
    ) {
        /**
         * Convenience factory for callers that only want Extended Thinking.
         * Equivalent to {@code new ChatOptions(ThinkingConfig.builder()
         * .enabled(true).budgetTokens(budgetTokens).build(), null, null)}.
         */
        public static ChatOptions thinkingEnabled(int budgetTokens) {
            return new ChatOptions(
                    LlmChatRequest.ThinkingConfig.builder()
                            .enabled(true)
                            .budgetTokens(budgetTokens)
                            .build(),
                    null,
                    null
            );
        }

        /** Sentinel "no overrides" — equivalent to the legacy {@link #chat(String)} path. */
        public static ChatOptions defaults() {
            return new ChatOptions(null, null, null);
        }
    }
}
