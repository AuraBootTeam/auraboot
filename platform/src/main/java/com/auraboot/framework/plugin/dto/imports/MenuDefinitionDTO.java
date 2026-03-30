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
 * DTO for importing menu definitions from plugin manifest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuDefinitionDTO {

    /**
     * Menu code (unique within tenant).
     * Required.
     */
    private String code;

    /**
     * Menu name.
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
     * Route path.
     */
    private String path;

    /**
     * Component path for frontend routing.
     */
    private String component;

    /**
     * Menu icon name.
     */
    private String icon;

    /**
     * Menu type: 1 = Directory, 2 = Menu, 3 = Button.
     */
    @Builder.Default
    private Integer type = 2;

    /**
     * Parent menu code.
     */
    private String parentCode;

    /**
     * Permission code required to see this menu.
     */
    private String permissionCode;

    /**
     * Whether the menu is visible.
     */
    @Builder.Default
    private Boolean visible = true;

    /**
     * Display order.
     */
    @Builder.Default
    private Integer orderNo = 0;

    /**
     * I18n key for the menu name.
     */
    private String i18nKey;

    /**
     * Redirect path.
     */
    private String redirect;

    /**
     * Page PID for page binding.
     */
    private String pagePid;

    /**
     * Page key for linking to a page defined in pages.json.
     * During import, this will be resolved to pagePid by looking up the page.
     */
    private String pageKey;

    /**
     * Model code for dynamic CRUD page binding.
     * When set, the menu will render the dynamic list page for this model.
     */
    private String modelCode;

    /**
     * Page kind for dynamic rendering.
     * Supported values: list, detail, form, dashboard.
     * Default: list.
     */
    @Builder.Default
    private String kind = "list";

    /**
     * Child menus.
     */
    private List<MenuDefinitionDTO> children;

    /**
     * Extension properties.
     */
    private Map<String, Object> extension;

    /**
     * Captures all "name:*" localized name entries from JSON (e.g. "name:ja-JP", "name:ko-KR").
     * This allows arbitrary locale support beyond the hardcoded nameZhCN/nameEn fields.
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
            // Normalize "name:en" → "en-US", "name:zh-CN" → "zh-CN", others pass through
            String locale = key.substring("name:".length());
            if ("en".equals(locale)) locale = "en-US";
            localizedNames.put(locale, strVal);
            return;
        }
        unknownFields.put(key, value);
    }

    /**
     * Return a merged map of all localized names.
     * Includes entries from the dynamic localizedNames map plus the legacy nameZhCN/nameEn fields.
     */
    @JsonIgnore
    public Map<String, String> getAllLocalizedNames() {
        Map<String, String> result = new LinkedHashMap<>();
        if (localizedNames != null) {
            result.putAll(localizedNames);
        }
        if (nameZhCN != null && !nameZhCN.isBlank()) result.putIfAbsent("zh-CN", nameZhCN);
        if (nameEn != null && !nameEn.isBlank()) result.putIfAbsent("en-US", nameEn);
        return result;
    }

    /**
     * Validate menu definition has required fields.
     */
    public boolean isValid() {
        return code != null && !code.isBlank();
    }

    /**
     * Get effective name. Checks localizedNames first, then legacy fields, then fallback.
     */
    public String getEffectiveName() {
        if (localizedNames != null && !localizedNames.isEmpty()) {
            // Prefer zh-CN, then en-US, then first available
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
