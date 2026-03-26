package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DTO for i18n resource definitions in plugin import.
 *
 * JSON format (flat, each entry contains all translations):
 * <pre>
 * {
 *   "key": "model.pe_brand._meta.label",
 *   "zh-CN": "品牌",
 *   "en-US": "Brand",
 *   "source": "import",
 *   "refType": "model"
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class I18nDefinitionDTO {

    /**
     * i18n key, e.g. "model.pe_brand._meta.label"
     */
    private String key;

    /**
     * Chinese (Simplified) translation.
     */
    @JsonProperty("zh-CN")
    private String zhCN;

    /**
     * English translation.
     */
    @JsonProperty("en-US")
    private String enUS;

    /**
     * Japanese translation (optional).
     */
    @JsonProperty("ja-JP")
    private String jaJP;

    /**
     * Korean translation (optional).
     */
    @JsonProperty("ko-KR")
    private String koKR;

    /**
     * Source identifier, defaults to "import".
     */
    private String source;

    /**
     * Reference type: model, field, page (optional).
     */
    private String refType;

    /**
     * Additional translations for unsupported locales.
     * Captures any JSON property that looks like a locale (e.g., "fr-FR", "de-DE").
     */
    @JsonIgnore
    private Map<String, String> extraTranslations;

    @JsonAnySetter
    public void setExtra(String name, Object value) {
        // Capture locale-like keys (xx-XX pattern) as extra translations
        if (name != null && name.matches("[a-z]{2}-[A-Z]{2}") && value instanceof String) {
            if (extraTranslations == null) {
                extraTranslations = new LinkedHashMap<>();
            }
            extraTranslations.put(name, (String) value);
        }
    }

    /**
     * Get all translations as a map (locale -> value).
     */
    @JsonIgnore
    public Map<String, String> getAllTranslations() {
        Map<String, String> translations = new LinkedHashMap<>();
        if (zhCN != null) translations.put("zh-CN", zhCN);
        if (enUS != null) translations.put("en-US", enUS);
        if (jaJP != null) translations.put("ja-JP", jaJP);
        if (koKR != null) translations.put("ko-KR", koKR);
        if (extraTranslations != null) translations.putAll(extraTranslations);
        return translations;
    }

    @JsonIgnore
    public boolean isValid() {
        return key != null && !key.isBlank() && !getAllTranslations().isEmpty();
    }
}
