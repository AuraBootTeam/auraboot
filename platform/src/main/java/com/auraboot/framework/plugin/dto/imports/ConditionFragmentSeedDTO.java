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
 * Plugin-delivered reusable condition fragment seed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConditionFragmentSeedDTO {

    private String fragmentCode;
    private String fragmentName;
    private String description;
    private String scopeType;
    private String scopeRef;
    private String ownerModule;

    @Builder.Default
    private Boolean enabled = true;

    private JsonNode conditionSpec;

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
        return hasText(fragmentCode)
                && hasText(fragmentName)
                && conditionSpec != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
