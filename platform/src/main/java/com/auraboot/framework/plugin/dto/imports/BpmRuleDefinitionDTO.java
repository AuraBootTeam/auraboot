package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO for importing Drools rule definitions from plugin manifest
 * (mirrors {@link com.auraboot.framework.bpm.entity.BpmRule}).
 *
 * <p>A rule may carry DRL content inline via {@code ruleContent}, or point to a
 * companion file via {@code ruleContentFile} (relative path within the plugin
 * directory). Supplying both is an import-time error — see
 * {@code PluginDirectoryLoader}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpmRuleDefinitionDTO {

    /** Rule code (unique within tenant). Required. */
    private String ruleCode;

    /** Display name. */
    private String ruleName;

    /** Rule type: CONDITION | ASSIGNEE | VALIDATION | CUSTOM. */
    private String ruleType;

    /** Inline DRL content. Mutually exclusive with {@link #ruleContentFile}. */
    private String ruleContent;

    /**
     * Relative path to a DRL file inside the plugin directory, e.g.
     * {@code rules/wd_leave_validation.drl}. The loader slurps the file
     * and fills {@link #ruleContent} before import.
     */
    private String ruleContentFile;

    /** Optional JSON-shaped input schema documentation. */
    private Map<String, Object> inputSchema;

    /** Optional JSON-shaped output schema documentation. */
    private Map<String, Object> outputSchema;

    /** Human-readable description. */
    private String description;

    /** Whether the rule is enabled (defaults to true). */
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
        return ruleCode != null && !ruleCode.isBlank();
    }
}
