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
 * DTO for importing dictionary definitions from plugin manifest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DictDefinitionDTO {

    /**
     * Dictionary code (unique within tenant).
     * Required.
     */
    private String code;

    /**
     * Dictionary name.
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
     * Dictionary description.
     */
    private String description;

    /**
     * Dictionary type: STATIC, DYNAMIC, TREE, etc.
     */
    @Builder.Default
    private String dictType = "static";

    /**
     * Dictionary items.
     */
    private List<DictItemDTO> items;

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
     * Validate dictionary definition has required fields.
     */
    public boolean isValid() {
        return code != null && !code.isBlank();
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
        return name != null ? name : code;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DictItemDTO {
        /**
         * Item value (unique within dictionary).
         * Required.
         */
        private String value;

        /**
         * Item label.
         */
        private String label;

        /**
         * Localized labels.
         */
        @JsonProperty("label:zh-CN")
        private String labelZhCN;

        @JsonProperty("label:en")
        private String labelEn;

        /**
         * Parent value for tree structure.
         */
        private String parentValue;

        /**
         * Sort order.
         */
        @Builder.Default
        private Integer sortNo = 0;

        /**
         * Whether the item is enabled.
         */
        @Builder.Default
        private String status = "enabled";

        /**
         * Extra properties (accepts both "extra" and "extension" from JSON).
         */
        @com.fasterxml.jackson.annotation.JsonAlias("extension")
        private Map<String, Object> extra;

        /**
         * Captures all "label:*" localized label entries from JSON beyond the hardcoded labelZhCN/labelEn.
         */
        @JsonIgnore
        @Builder.Default
        private Map<String, String> localizedLabels = new LinkedHashMap<>();

        @JsonIgnore
        private Map<String, Object> unknownFields;

        @JsonAnySetter
        public void setUnknownField(String key, Object value) {
            if (unknownFields == null) {
                unknownFields = new HashMap<>();
            }
            if (key != null && key.startsWith("label:") && value instanceof String strVal) {
                if (localizedLabels == null) {
                    localizedLabels = new LinkedHashMap<>();
                }
                String locale = key.substring("label:".length());
                if ("en".equals(locale)) locale = "en-US";
                localizedLabels.put(locale, strVal);
                return;
            }
            unknownFields.put(key, value);
        }

        @JsonIgnore
        public Map<String, String> getAllLocalizedLabels() {
            Map<String, String> result = new LinkedHashMap<>();
            if (localizedLabels != null) result.putAll(localizedLabels);
            if (labelZhCN != null && !labelZhCN.isBlank()) result.putIfAbsent("zh-CN", labelZhCN);
            if (labelEn != null && !labelEn.isBlank()) result.putIfAbsent("en-US", labelEn);
            return result;
        }

        /**
         * Get effective label. Checks localizedLabels first, then legacy fields, then fallback.
         */
        public String getEffectiveLabel() {
            if (localizedLabels != null && !localizedLabels.isEmpty()) {
                String val = localizedLabels.get("zh-CN");
                if (val != null && !val.isBlank()) return val;
                val = localizedLabels.get("en-US");
                if (val != null && !val.isBlank()) return val;
                for (String v : localizedLabels.values()) {
                    if (v != null && !v.isBlank()) return v;
                }
            }
            if (labelZhCN != null && !labelZhCN.isBlank()) {
                return labelZhCN;
            }
            if (labelEn != null && !labelEn.isBlank()) {
                return labelEn;
            }
            return label != null ? label : value;
        }
    }
}
