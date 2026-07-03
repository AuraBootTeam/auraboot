package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Plugin-delivered Decision Runtime seed definition.
 *
 * <p>This is intentionally an extension resource, like BPM Drools rules and SLA configs, because
 * DRT definitions are governed by their own lifecycle tables instead of {@code ab_plugin_resource}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionDefinitionSeedDTO {

    private String decisionCode;
    private String decisionName;
    private String description;
    private String scopeType;
    private String scopeRef;
    private String ownerModule;

    @Builder.Default
    private Boolean enabled = true;

    private String versionTag;
    private String kind;
    private String runtimeAdapter;
    private JsonNode contentJson;
    private JsonNode inputSchemaJson;
    private JsonNode outputSchemaJson;
    private JsonNode contextSchemaJson;

    @Builder.Default
    private Boolean publish = true;

    @JsonIgnore
    private Map<String, Object> unknownFields;

    @JsonAnySetter
    public void setUnknownField(String key, Object value) {
        if (unknownFields == null) {
            unknownFields = new HashMap<>();
        }
        unknownFields.put(key, value);
    }

    @JsonIgnore
    public boolean isPublish() {
        return publish == null || publish;
    }

    @JsonIgnore
    public boolean isValid() {
        return hasText(decisionCode)
                && hasText(decisionName)
                && hasText(kind)
                && hasText(runtimeAdapter)
                && contentJson != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
