package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Unified LLM chat response — provider-agnostic.
 * Each LlmProvider normalizes their API response into this format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChatResponse {

    /** "end_turn", "tool_use", "max_tokens" — normalized across providers */
    private String stopReason;

    private List<ContentBlock> content;
    private int inputTokens;
    private int outputTokens;

    /**
     * Tokens written to the provider's prompt cache on this request.
     * For Anthropic this is {@code cache_creation_input_tokens} (billed at 1.25x base rate).
     * Other providers default to 0.
     */
    private int cacheCreationInputTokens;

    /**
     * Tokens served from the provider's prompt cache on this request.
     * For Anthropic this is {@code cache_read_input_tokens} (billed at 0.1x base rate).
     * Other providers default to 0.
     */
    private int cacheReadInputTokens;

    /**
     * Provider-side warnings produced while building / executing this request
     * (e.g. Extended Thinking budget required auto-extension of {@code max_tokens}
     * because the caller's value would have triggered a 400). Empty / null when
     * the call was clean. Surfaced explicitly instead of silent log-only
     * behaviour so downstream callers can route the message to the user (P0-2
     * M9 — replaces the prior "log.warn + auto-extend" silent fallback that
     * violated the no-fallback red line).
     *
     * <p>Null when no warnings were emitted (most calls); never an empty list,
     * to keep the JSON wire form compact via {@code @JsonInclude(NON_NULL)}
     * where applicable.
     */
    private List<String> warnings;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ContentBlock {
        /** "text", "tool_use", or "thinking" (Anthropic Extended Thinking only). */
        private String type;
        private String text;
        private String id;          // tool call id
        private String name;        // tool name
        private Map<String, Object> input;  // tool input args

        /**
         * Anthropic Extended Thinking — populated when {@code type == "thinking"}.
         * The raw chain-of-thought prose the model produced. Other providers
         * never set this.
         */
        private String thinking;

        /** Opaque signature carried alongside a thinking block (Anthropic only). */
        private String signature;
    }
}
