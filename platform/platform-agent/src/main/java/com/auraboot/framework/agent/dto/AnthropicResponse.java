package com.auraboot.framework.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicResponse {
    private String id;
    private String type;
    private String role;
    private List<ContentBlock> content;
    private String model;
    private String stop_reason;
    private Usage usage;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentBlock {
        private String type;
        private String text;
        private String id;
        private String name;
        private Map<String, Object> input;

        /**
         * Anthropic Extended Thinking returns {@code {type:"thinking",
         * thinking:"...prose...", signature:"..."}}. Both fields are present
         * only on blocks where {@code type=="thinking"}; otherwise they remain
         * {@code null}.
         */
        private String thinking;

        /**
         * Opaque signature accompanying a thinking block. Anthropic returns it
         * so callers can pass it back on subsequent requests to resume from a
         * cached thinking trace; we currently store it for forward
         * compatibility but do not yet round-trip it.
         */
        private String signature;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        private int input_tokens;
        private int output_tokens;

        /**
         * Tokens written to the ephemeral cache on this request. Billed at
         * 1.25x the base input rate (Anthropic 2024-06+ pricing).
         */
        private int cache_creation_input_tokens;

        /**
         * Tokens served from the ephemeral cache on this request. Billed at
         * 0.1x the base input rate.
         */
        private int cache_read_input_tokens;
    }
}
