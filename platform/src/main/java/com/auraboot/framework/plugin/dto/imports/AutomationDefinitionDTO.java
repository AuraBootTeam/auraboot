package com.auraboot.framework.plugin.dto.imports;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.TriggerConfig;
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
 * Plugin-delivered automation seed definition.
 *
 * <p>Automations are extension resources, like SLA configs and DRT definitions, because their
 * runtime lifecycle is owned by the automation module rather than {@code ab_plugin_resource}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationDefinitionDTO {

    /** Stable plugin seed key. Used for import idempotency and Rule Center consumer refs. */
    private String automationKey;

    private String name;
    private String description;
    private String modelCode;
    private String triggerType;
    private TriggerConfig triggerConfig;
    private String triggerCondition;
    private List<AutomationAction> actions;
    private Map<String, Object> flowConfig;

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

    @JsonIgnore
    public boolean isValid() {
        return hasText(automationKey)
                && hasText(name)
                && hasText(modelCode)
                && hasText(triggerType);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
