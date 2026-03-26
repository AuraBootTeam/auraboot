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
}
