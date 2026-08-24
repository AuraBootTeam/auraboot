package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/** Plugin-owned runtime addition to a slot declared by an existing base page. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageContributionDefinitionDTO {

    private String id;
    private String targetPageKey;
    private String slotId;
    private String kind;
    @Builder.Default
    private Integer priority = 0;
    private Map<String, Object> payload;

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
    public boolean isValid() {
        return hasText(id) && hasText(targetPageKey) && hasText(slotId)
                && hasText(kind) && payload != null && !payload.isEmpty();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
