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
 * Plugin-delivered EventPolicy definition and first version seed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventPolicySeedDTO {

    private String policyCode;
    private String policyName;
    private String eventType;
    private String targetType;
    private String targetKey;

    @Builder.Default
    private Boolean enabled = true;

    private String phase;
    private String matchMode;
    private String executionMode;
    private String failureStrategy;
    private String conflictStrategy;
    private String dedupStrategy;
    private JsonNode rulesJson;

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
        return hasText(policyCode)
                && hasText(policyName)
                && hasText(eventType)
                && hasText(targetType)
                && hasText(targetKey)
                && rulesJson != null
                && rulesJson.isArray()
                && !rulesJson.isEmpty();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
