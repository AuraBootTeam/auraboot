package com.auraboot.framework.semantic.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parsed and validated representation of a single {@code *.semantic.yml} file.
 *
 * <p>Constructed by {@code SemanticYamlParser}. Direct mapping of the
 * Draft 2020-12 schema in {@code semantic-v0.1.schema.json}.
 */
@Data
@NoArgsConstructor
public class SemanticModelDTO {

    private String version;

    @JsonProperty("semantic_model")
    private ModelHeader semanticModel;

    private List<EntityDTO> entities = new ArrayList<>();
    private List<DimensionDTO> dimensions = new ArrayList<>();
    private List<MeasureDTO> measures = new ArrayList<>();
    private List<MetricDTO> metrics = new ArrayList<>();

    @JsonProperty("access_policies")
    private List<AccessPolicyDTO> accessPolicies = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class ModelHeader {
        private String code;
        private Map<String, String> label;
        private String description;

        @JsonProperty("model_ref")
        private String modelRef;

        @JsonProperty("primary_entity")
        private String primaryEntity;
    }
}
