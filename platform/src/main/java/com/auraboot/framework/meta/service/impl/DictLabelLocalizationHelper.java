package com.auraboot.framework.meta.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.auraboot.framework.meta.dto.CascadeDictResult;
import com.auraboot.framework.meta.dto.DictDataResult;
import com.auraboot.framework.meta.dto.DictItemData;
import com.auraboot.framework.meta.dto.DictTreeNode;

import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>Lives outside {@code DictVersionServiceImpl#loadDictByStrategy} and the
 * cascade services because those methods are cached by tenant+code+version
 * only; locale resolution must happen post-cache. Kept as a static helper so
 * controllers and tests share one implementation without extra bean wiring
 * (same pattern as {@link PageSchemaLocalizationHelper}). The same resolution
 * is applied to the cascade reads (children / tree / query), whose DTOs carry
 * the item extension since this helper gained its cascade overloads.
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
        return item == null ? null : resolveLocalizedLabel(item.getExtension(), locale);
    }

    /**
     * Extension-shaped overload of {@link #resolveLocalizedLabel} used by the
     * cascade item and tree DTOs ({@code extension} is carried as
     * {@code Object}, either a {@link Map} or a {@link JsonNode}).
     */
    static String resolveLocalizedLabel(Object extension, String locale) {
        if (extension == null || locale == null || locale.isBlank()) {
            return null;
        }
        Object labels = readLabelsMap(extension);
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

    /**
     * Rewrites the labels of a cascade children list in place for the request
     * locale. A null list, null/blank locale, or a missing localized entry
     * leaves the stored labels untouched.
     */
    public static void localize(List<DictItemData> items, String locale) {
        if (items == null || locale == null || locale.isBlank()) {
            return;
        }
        for (DictItemData item : items) {
            String localized = resolveLocalizedLabel(item.getExtension(), locale);
            if (localized != null) {
                item.setLabel(localized);
            }
        }
    }

    /**
     * Rewrites the labels of a cascade tree (recursively, children included)
     * in place for the request locale. A null node, null/blank locale, or a
     * missing localized entry leaves the stored labels untouched.
     */
    public static void localize(DictTreeNode node, String locale) {
        if (node == null || locale == null || locale.isBlank()) {
            return;
        }
        String localized = resolveLocalizedLabel(node.getExtension(), locale);
        if (localized != null) {
            node.setLabel(localized);
        }
        if (node.hasChildren()) {
            for (DictTreeNode child : node.getChildren()) {
                localize(child, locale);
            }
        }
    }

    /**
     * Rewrites the labels of a cascade query result (items, tree, and the
     * value→label map) in place for the request locale. The level/parent maps
     * hold the same item instances as {@code items}, so in-place label
     * rewrites cover them without extra passes.
     */
    public static void localize(CascadeDictResult result, String locale) {
        if (result == null || locale == null || locale.isBlank()) {
            return;
        }
        localize(result.getItems(), locale);
        if (result.getTree() != null) {
            localize(result.getTree(), locale);
        }
        Map<String, String> itemMap = result.getItemMap();
        if (itemMap != null && result.getItems() != null) {
            itemMap.clear();
            for (DictItemData item : result.getItems()) {
                itemMap.put(item.getValue(), item.getLabel());
            }
        }
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
