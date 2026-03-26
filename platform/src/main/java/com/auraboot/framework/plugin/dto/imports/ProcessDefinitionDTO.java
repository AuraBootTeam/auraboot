package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DTO for importing BPM process definitions from plugin manifest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessDefinitionDTO {

    /**
     * Process key (unique identifier for the process).
     * Required.
     */
    private String key;

    /**
     * Process name.
     */
    private String name;

    /**
     * Localized names.
     */
    @JsonProperty("name:zh-CN")
    private String nameZhCN;

    @JsonProperty("name:en")
    private String nameEn;

    /**
     * Process description.
     */
    private String description;

    /**
     * BPMN file path relative to plugin package.
     * Required.
     */
    private String bpmnFile;

    /**
     * Inline BPMN content (alternative to bpmnFile).
     */
    private String bpmnContent;

    /**
     * Process category for organization.
     */
    private String category;

    /**
     * Form bindings for user tasks.
     * Key: user task ID, Value: form configuration.
     */
    private Map<String, FormBinding> formBindings;

    /**
     * Business data bindings.
     */
    private List<BusinessDataBinding> businessDataBindings;

    /**
     * Whether to auto-deploy when imported.
     */
    @Builder.Default
    private Boolean autoDeploy = true;

    /**
     * Designer JSON (React Flow format) as alternative to bpmnFile/bpmnContent.
     * Can be converted to BPMN XML at deployment time.
     */
    private Map<String, Object> designerJson;

    /**
     * Extension properties.
     */
    private Map<String, Object> extension;

    /**
     * Captures all "name:*" localized name entries from JSON beyond the hardcoded nameZhCN/nameEn.
     */
    @JsonIgnore
    @Builder.Default
    private Map<String, String> localizedNames = new LinkedHashMap<>();

    @JsonIgnore
    private Map<String, Object> unknownFields;

    @JsonAnySetter
    public void setUnknownField(String key, Object value) {
        if (unknownFields == null) {
            unknownFields = new HashMap<>();
        }
        if (key != null && key.startsWith("name:") && value instanceof String strVal) {
            if (localizedNames == null) {
                localizedNames = new LinkedHashMap<>();
            }
            String locale = key.substring("name:".length());
            if ("en".equals(locale)) locale = "en-US";
            localizedNames.put(locale, strVal);
            return;
        }
        unknownFields.put(key, value);
    }

    @JsonIgnore
    public Map<String, String> getAllLocalizedNames() {
        Map<String, String> result = new LinkedHashMap<>();
        if (localizedNames != null) result.putAll(localizedNames);
        if (nameZhCN != null && !nameZhCN.isBlank()) result.putIfAbsent("zh-CN", nameZhCN);
        if (nameEn != null && !nameEn.isBlank()) result.putIfAbsent("en-US", nameEn);
        return result;
    }

    /**
     * Validate process definition has required fields.
     */
    public boolean isValid() {
        return key != null && !key.isBlank()
                && (bpmnFile != null || bpmnContent != null || designerJson != null);
    }

    /**
     * Get effective name. Checks localizedNames first, then legacy fields, then fallback.
     */
    public String getEffectiveName() {
        if (localizedNames != null && !localizedNames.isEmpty()) {
            String val = localizedNames.get("zh-CN");
            if (val != null && !val.isBlank()) return val;
            val = localizedNames.get("en-US");
            if (val != null && !val.isBlank()) return val;
            for (String v : localizedNames.values()) {
                if (v != null && !v.isBlank()) return v;
            }
        }
        if (nameZhCN != null && !nameZhCN.isBlank()) {
            return nameZhCN;
        }
        if (nameEn != null && !nameEn.isBlank()) {
            return nameEn;
        }
        return name != null ? name : key;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormBinding {
        /**
         * Form type: MODEL, PAGE, CUSTOM.
         */
        private String formType;

        /**
         * Model code for MODEL form type.
         */
        private String modelCode;

        /**
         * Page PID for PAGE form type.
         */
        private String pagePid;

        /**
         * Custom form component name.
         */
        private String customComponent;

        /**
         * Fields to display/edit.
         */
        private List<String> fields;

        /**
         * Read-only fields.
         */
        private List<String> readOnlyFields;

        /**
         * Validation configuration.
         */
        private Map<String, Object> validation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BusinessDataBinding {
        /**
         * Model code for business data.
         */
        private String modelCode;

        /**
         * Binding type: START_FORM, PROCESS_VARIABLE, etc.
         */
        private String bindingType;

        /**
         * Variable name in process.
         */
        private String variableName;

        /**
         * Expression for data extraction.
         */
        private String expression;
    }
}
