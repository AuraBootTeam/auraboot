package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Unified LLM chat request — provider-agnostic.
 * Each LlmProvider translates this into their API format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChatRequest {

    private String model;
    private String providerCode;
    private String systemPrompt;
    private List<Message> messages;
    private List<Tool> tools;
    private int maxTokens;

    /**
     * Optional Anthropic Extended Thinking configuration. When {@code null} the
     * provider must NOT add a {@code thinking} field to the wire request — this
     * keeps existing chat callers byte-identical with the pre-P0-2 behaviour.
     *
     * <p>Capability gating happens in the provider: even when this is set with
     * {@code enabled=true}, providers that cannot handle thinking (legacy
     * Claude 3, OpenAI/DeepSeek/Qwen, etc.) silently drop it.
     */
    private ThinkingConfig thinking;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Message {
        private String role;        // "user", "assistant"
        private Object content;     // String or List<ContentBlock>
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ContentBlock {
        private String type;        // "text", "tool_use", "tool_result"
        private String text;
        private String id;          // tool_use id
        private String name;        // tool name
        private Object input;       // tool input
        private String toolUseId;   // for tool_result
        private Object result;      // tool result content
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Tool {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;
        private Map<String, Object> nativeToolConfig; // For LLM_NATIVE tools — passed directly to provider
    }

    /**
     * Anthropic Extended Thinking knob. Only honoured by Claude Sonnet 4.6+,
     * Opus 4.x, and Haiku 4.x — see {@code AnthropicLlmProvider#supportsThinking}.
     *
     * <p>{@code budgetTokens} is the maximum tokens the model may spend in its
     * private thinking block. Anthropic requires
     * {@code request.max_tokens > thinking.budget_tokens}; if not, the provider
     * auto-extends max_tokens to {@code budget + 4096} to avoid HTTP 400.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ThinkingConfig {
        private boolean enabled;
        @Builder.Default
        private int budgetTokens = 10_000;
    }
}
