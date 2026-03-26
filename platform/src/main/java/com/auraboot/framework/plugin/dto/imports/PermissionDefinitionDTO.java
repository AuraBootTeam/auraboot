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
 * DTO for importing permission definitions from plugin manifest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionDefinitionDTO {

    /**
     * Permission code (unique within tenant).
     * Required.
     */
    private String code;

    /**
     * Permission name.
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
     * Permission description.
     */
    private String description;

    /**
     * Permission category for grouping.
     */
    private String category;

    /**
     * Resource type: MODEL, PAGE, API, MENU, etc.
     */
    private String resourceType;

    /**
     * Resource code this permission applies to.
     */
    private String resourceCode;

    /**
     * Action: CREATE, READ, UPDATE, DELETE, EXECUTE, etc.
     */
    private String action;

    /**
     * Data scope type: ALL, OWN, DEPT, CUSTOM, etc.
     */
    private String dataScopeType;

    /**
     * Data scope configuration.
     */
    private Map<String, Object> dataScopeConfig;

    /**
     * Parent permission code for hierarchy.
     */
    private String parentCode;

    /**
     * Tags for filtering.
     */
    private List<String> tags;

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
     * Validate permission definition has required fields.
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
}
