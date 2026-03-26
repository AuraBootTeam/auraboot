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

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ContentBlock {
        private String type;        // "text" or "tool_use"
        private String text;
        private String id;          // tool call id
        private String name;        // tool name
        private Map<String, Object> input;  // tool input args
    }
}
