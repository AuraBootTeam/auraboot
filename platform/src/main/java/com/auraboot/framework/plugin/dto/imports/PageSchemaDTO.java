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
 * DTO for importing page schema definitions from plugin manifest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageSchemaDTO {

    /**
     * Page key (unique identifier).
     * Required.
     */
    private String pageKey;

    /**
     * Page name.
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
     * Page description.
     */
    private String description;

    /**
     * Page kind: list, form, detail, dashboard.
     */
    private String kind;

    /**
     * Render profile. Default: admin.
     */
    private String profile;

    /**
     * Localized title as map.
     */
    private Map<String, Object> title;

    /**
     * Layout config.
     */
    private Map<String, Object> layout;

    /**
     * Content blocks array.
     */
    private List<Object> blocks;

    /**
     * Associated model code (for MODEL category pages).
     */
    private String modelCode;

    /**
     * DSL schema format version (single integer).
     * Default: 1 (baseline). Incremented only on breaking DSL format changes.
     */
    private Integer schemaVersion;

    /**
     * Page meta information.
     */
    private Map<String, Object> metaInfo;

    /**
     * Whether this is a page template.
     */
    @Builder.Default
    private Boolean isTemplate = false;

    /**
     * Template category if isTemplate is true.
     */
    private String templateCategory;

    /**
     * Namespace for page isolation.
     */
    @Builder.Default
    private String namespace = "default";

    /**
     * Environment: dev, test, prod.
     */
    @Builder.Default
    private String env = "prod";

    /**
     * Sort weight for ordering.
     */
    @Builder.Default
    private Integer sortWeight = 0;

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
     * Validate page schema has required fields.
     */
    public boolean isValid() {
        return pageKey != null && !pageKey.isBlank()
                && kind != null && !kind.isBlank();
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
        return name != null ? name : pageKey;
    }
}
