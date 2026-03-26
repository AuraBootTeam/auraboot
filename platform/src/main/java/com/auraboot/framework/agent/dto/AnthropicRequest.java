package com.auraboot.framework.agent.dto;

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
public class AnthropicRequest {

    private String model;
    private int max_tokens;
    private String system;
    private List<Message> messages;
    private List<Tool> tools;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Message {
        private String role;
        private Object content;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
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
    public static class Tool {
        private String name;
        private String description;
        private Map<String, Object> input_schema;
    }
}
