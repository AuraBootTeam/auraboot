package com.auraboot.framework.agent.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class AnthropicResponse {
    private String id;
    private String type;
    private String role;
    private List<ContentBlock> content;
    private String model;
    private String stop_reason;
    private Usage usage;

    @Data
    public static class ContentBlock {
        private String type;
        private String text;
        private String id;
        private String name;
        private Map<String, Object> input;
    }

    @Data
    public static class Usage {
        private int input_tokens;
        private int output_tokens;
    }
}
