package com.auraboot.framework.semantic.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Catalog response of {@code GET /api/semantic/meta}.
 *
 * <p>Used by:
 * <ul>
 *   <li>ChatBI v2 prompt construction (LLM grounding,见 PRD 17 §7.1)</li>
 *   <li>UI metric pickers</li>
 *   <li>External BI tools introspection</li>
 * </ul>
 */
@Data
@NoArgsConstructor
public class SemanticMetaResponse {

    private List<ModelMeta> models = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class ModelMeta {
        private String pid;
        private String code;
        private String pluginCode;
        private String version;
        private Map<String, String> label;
        private String description;
        private String modelRef;
        private List<MetricMeta> metrics = new ArrayList<>();
        private List<DimensionMeta> dimensions = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    public static class MetricMeta {
        private String pid;
        private String code;
        private String type;
        private Map<String, String> label;
        private String description;
        private List<String> requiredPermissions;
    }

    @Data
    @NoArgsConstructor
    public static class DimensionMeta {
        private String pid;
        private String code;
        private String type;
        private Map<String, String> label;
        private List<String> timeGrains;
        private boolean primaryTime;
    }
}
