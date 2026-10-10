package com.auraboot.framework.meta.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.auraboot.framework.meta.dto.DictDataResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves runtime dict item labels per request locale.
 *
 * <p>Plugin manifests carry localized dict item labels as "label:zh-CN" /
 * "label:en" entries. The release read path
 * ({@code ApplicationRuntimeDefinitionCatalog#findDict}) and the plugin import
 * path ({@code PluginResourceImporterImpl#importDict}) both persist them as a
 * "labels" map inside the item extension, while the stored item label keeps
 * only one locale. This helper rewrites the served label from that map for the
 * request locale (query param &gt; X-Locale &gt; Accept-Language, per
 * {@code I18nLocaleResolver}) and keeps the stored label when no matching
 * entry exists, so dictionaries without translations are unchanged.
 *
 * <p>Lives outside {@code DictVersionServiceImpl#loadDictByStrategy} because
 * that method is cached by tenant+code+version only; locale resolution must
 * happen post-cache. Kept as a static helper so controllers and tests share
 * one implementation without extra bean wiring (same pattern as
 * {@link PageSchemaLocalizationHelper}).
 */
public final class DictLabelLocalizationHelper {

    /** Extension key holding the locale → label map written at import/release time. */
    public static final String LABELS_EXTENSION_KEY = "labels";

    private DictLabelLocalizationHelper() {
    }

    /**
     * Rewrites item labels (and the value→label map) of the given dict data
     * in place for the request locale. A null/blank locale or a missing
     * localized entry leaves the stored label untouched.
     */
    public static void localize(DictDataResult result, String locale) {
        if (result == null || locale == null || locale.isBlank() || result.getItems() == null) {
            return;
        }
        Map<String, Object> itemMap = result.getItemMap();
        Map<String, Object> rebuiltItemMap = itemMap == null ? null : new LinkedHashMap<>();
        for (DictDataResult.DictItemData item : result.getItems()) {
            String localized = resolveLocalizedLabel(item, locale);
            if (localized != null) {
                item.setLabel(localized);
            }
            if (rebuiltItemMap != null) {
                rebuiltItemMap.put(String.valueOf(item.getValue()), item.getLabel());
            }
        }
        if (rebuiltItemMap != null) {
            result.setItemMap(rebuiltItemMap);
        }
    }

    /**
     * Returns the localized label for the given locale, or {@code null} when
     * the extension carries no usable "labels" entry (exact locale first, then
     * the language-only fallback, e.g. "en-US" → "en").
     */
    static String resolveLocalizedLabel(DictDataResult.DictItemData item, String locale) {
        if (item == null || locale == null || locale.isBlank()) {
            return null;
        }
        Object labels = readLabelsMap(item.getExtension());
        if (labels == null) {
            return null;
        }
        String exact = pickLabel(labels, locale);
        if (exact != null) {
            return exact;
        }
        int dash = locale.indexOf('-');
        if (dash > 0) {
            return pickLabel(labels, locale.substring(0, dash));
        }
        return null;
    }

    /** Non-blank label for the locale key, or {@code null}. */
    private static String pickLabel(Object labels, String key) {
        Object value = labels instanceof Map<?, ?> map ? map.get(key) : null;
        if (value == null) {
            return null;
        }
        String label = String.valueOf(value);
        return label.isBlank() ? null : label;
    }

    /** Extracts the "labels" map from a Map- or JsonNode-shaped extension. */
    private static Map<?, ?> readLabelsMap(Object extension) {
        if (extension instanceof Map<?, ?> map) {
            return map.get(LABELS_EXTENSION_KEY) instanceof Map<?, ?> labels ? labels : null;
        }
        if (extension instanceof JsonNode node && node.isObject()) {
            JsonNode labels = node.get(LABELS_EXTENSION_KEY);
            if (labels != null && labels.isObject()) {
                Map<String, Object> result = new LinkedHashMap<>();
                labels.fields().forEachRemaining(entry -> {
                    JsonNode value = entry.getValue();
                    if (value != null && value.isValueNode()) {
                        result.put(entry.getKey(), value.asText());
                    }
                });
                return result;
            }
        }
        return null;
    }
}
