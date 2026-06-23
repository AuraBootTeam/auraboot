package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.ArrayList;
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
     * Page-level data source registry for DSL v4 workbench pages. Persisted through
     * page extension data and exposed as a first-class DTO field for frontend runtime.
     */
    private Map<String, Object> dataSources;

    /**
     * Optional override for the edit-mode record-prefill fetch (e.g.
     * {@code {"endpoint": "/api/qr/{recordPid}"}}). Declared as a first-class field so
     * import validation recognizes it instead of rejecting it as
     * {@code S-PAGE-UNKNOWN-FIELDS}. Consumed by the form renderer to load the existing
     * record from a custom endpoint (skipTableCreation models served by a custom REST API).
     */
    private Map<String, Object> recordSource;

    /**
     * Content blocks.
     *
     * <p>Accepts both array form {@code [...]} and single-object form {@code {...}}.
     * LLM output sometimes produces a bare object here; the flexible deserializer
     * wraps it in a single-element list so downstream consumers always see a
     * {@code List}.</p>
     */
    @JsonDeserialize(using = PageSchemaDTO.BlocksFlexibleDeserializer.class)
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
     * Mobile UX profile. Stored under extension.mobileUx during import so the
     * existing page schema table can carry the profile without a schema change.
     */
    private Map<String, Object> mobileUx;

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
                && kind != null && !kind.isBlank()
                && layout != null && !layout.isEmpty()
                && blocks != null && !blocks.isEmpty();
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

    // =========================================================================
    // Flexible deserializer for the blocks field
    // =========================================================================

    /**
     * Deserializes the {@code blocks} JSON value into {@code List<Object>} regardless of
     * whether the source JSON contains an array ({@code [...]}) or a bare object
     * ({@code {...}}).
     *
     * <ul>
     *   <li>Array token → deserialize each element and collect into list.</li>
     *   <li>Object token → deserialize the single object and wrap in a one-element list.</li>
     *   <li>Null token → return {@code null}.</li>
     * </ul>
     *
     * <p>This handles LLM output that occasionally produces a single root block object
     * instead of an array, which would otherwise cause a Jackson
     * {@code MismatchedInputException} when binding to {@code List<Object>}.</p>
     */
    static class BlocksFlexibleDeserializer extends StdDeserializer<List<Object>> {

        BlocksFlexibleDeserializer() {
            super(List.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Object> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();

            if (token == JsonToken.START_ARRAY) {
                // Normal array form — let Jackson read each element generically
                List<Object> result = new ArrayList<>();
                p.nextToken(); // move past '['
                while (p.currentToken() != JsonToken.END_ARRAY) {
                    Object element = ctxt.readValue(p, Object.class);
                    result.add(element);
                    p.nextToken();
                }
                return result;
            } else if (token == JsonToken.START_OBJECT) {
                // Bare object form (LLM emitted a single block, not wrapped in array)
                Object singleBlock = ctxt.readValue(p, Object.class);
                List<Object> result = new ArrayList<>(1);
                result.add(singleBlock);
                return result;
            } else if (token == JsonToken.VALUE_NULL) {
                return null;
            }

            // Fallback: delegate to default deserializer for any other token
            return (List<Object>) ctxt.findNonContextualValueDeserializer(
                    ctxt.constructType(List.class)).deserialize(p, ctxt);
        }
    }

}
