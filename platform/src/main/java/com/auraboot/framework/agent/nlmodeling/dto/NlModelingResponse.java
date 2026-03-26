package com.auraboot.framework.agent.nlmodeling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for NL Modeling generate/refine endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NlModelingResponse {

    /** Session ID for conversational refinement continuity */
    private String sessionId;

    /** Suggested plugin code */
    private String pluginCode;

    /** Generated DSL resources */
    private Resources resources;

    /** Human-readable summary of what was generated */
    private String summary;

    /** Validation errors if any */
    private List<String> validationErrors;

    /** Token usage stats */
    private TokenUsage tokenUsage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Resources {
        private List<Map<String, Object>> models;
        private List<Map<String, Object>> fields;
        private List<Map<String, Object>> bindings;
        private List<Map<String, Object>> commands;
        private List<Map<String, Object>> pages;
        private List<Map<String, Object>> menus;
        private List<Map<String, Object>> i18n;
        private List<Map<String, Object>> permissions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenUsage {
        private int inputTokens;
        private int outputTokens;
        private double estimatedCostUsd;
    }
}
