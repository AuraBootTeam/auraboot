package com.auraboot.framework.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicRequest {

    private String model;
    private int max_tokens;

    /**
     * Anthropic accepts {@code system} as either a plain {@link String} or a list of
     * content blocks. We use {@link Object} here so the provider can pass either form,
     * and so we can attach {@code cache_control} markers to enable ephemeral
     * prompt caching on the system prompt segment.
     */
    private Object system;

    private List<Message> messages;
    private List<Tool> tools;

    /**
     * Anthropic Extended Thinking config. Serialized as
     * {@code {"thinking":{"type":"enabled","budget_tokens":N}}}. Omitted via
     * {@code @JsonInclude(NON_NULL)} when not set. Only valid for Claude
     * Sonnet 4.6+/Opus 4.x/Haiku 4.x — capability gating happens in the
     * provider before this field is populated.
     */
    private Thinking thinking;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        private Object content;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentBlock {
        private String type;
        private String text;
        private String id;
        private String name;
        private Object input;
        private String tool_use_id;
        private Object content_result;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {
        private String name;
        private String description;
        private Map<String, Object> input_schema;

        /**
         * Optional ephemeral cache marker. When set to
         * {@code {"type": "ephemeral"}} on the LAST tool of a request, Anthropic
         * caches everything up to and including the tools array, so subsequent
         * requests with identical system+tools pay 0.1x for the cached prefix.
         */
        private Map<String, Object> cache_control;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Thinking {
        /** Always {@code "enabled"} when present. Omit the whole field to disable. */
        private String type;
        private int budget_tokens;
    }
}
