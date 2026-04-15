package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DTO for importing SLA config definitions from plugin manifest
 * (mirrors {@link com.auraboot.framework.bpm.entity.SlaConfigEntity}).
 *
 * <p>Upsert key is {@code (tenantId, name)}; existing rows with the same name
 * within the tenant are updated in place.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlaConfigDefinitionDTO {

    /** Unique name within tenant. Required — used as the import upsert key. */
    private String name;

    /** Target type: PROCESS | NODE | TASK. */
    private String targetType;

    /** Target key (process key, node id, etc.). */
    private String targetKey;

    /** Associated domain code. */
    private String domainCode;

    /** Deadline calculation mode: FIXED | EXPRESSION | FIELD. */
    private String deadlineMode;

    /** Deadline value. */
    private String deadlineValue;

    /** Use business calendar for deadline calculation. */
    @Builder.Default
    private Boolean businessCalendar = false;

    /** Warning rules configuration. */
    private List<Map<String, Object>> warningRules;

    /** Associated model code. */
    private String modelCode;

    /** Field code for deadline source (when deadlineMode=FIELD). */
    private String deadlineField;

    /** Field code for priority source. */
    private String priorityField;

    /** Suspend policy: pause | continue | cancel. */
    @Builder.Default
    private String suspendPolicy = "pause";

    /** Whether the SLA config is enabled. */
    @Builder.Default
    private Boolean enabled = true;

    @JsonIgnore
    private Map<String, Object> unknownFields;

    @JsonAnySetter
    public void setUnknownField(String key, Object value) {
        if (unknownFields == null) {
            unknownFields = new HashMap<>();
        }
        unknownFields.put(key, value);
    }

    public boolean isValid() {
        return name != null && !name.isBlank();
    }
}
