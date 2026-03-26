package com.auraboot.framework.i18n.service;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * I18n Service - Multi-source internationalization data provider
 *
 * Data source priority (high to low):
 * 1. Database (via I18nResourceService)
 * 2. Compiled JSON file (i18n.{locale}.json)
 * 3. Legacy YAML file (i18n.{locale}.yaml) - for backward compatibility
 *
 * @author AuraBoot
 */
@Slf4j
@Service
public class I18nService {

    private static final String DEFAULT_LOCALE = "zh-CN";

    private final Cache<String, Map<String, Object>> i18nCache = Caffeine.newBuilder()
            .maximumSize(50)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();
    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Autowired(required = false)
    private I18nResourceService i18nResourceService;

    /**
     * Get i18n data for a locale
     *
     * Data loading priority:
     * 1. Try database via I18nResourceService
     * 2. Fall back to compiled JSON file
     * 3. Fall back to legacy YAML file
     *
     * @param locale The locale code (e.g., zh-CN, en-US)
     * @return Flattened map of i18n key -> value
     */
    public Map<String, Object> getI18nData(String locale) {
        if (locale == null || locale.isEmpty()) {
            locale = DEFAULT_LOCALE;
        }

        // Build tenant-aware cache key to prevent cross-tenant data leakage
        String cacheKey = buildCacheKey(locale);

        // Check cache first
        Map<String, Object> cached = i18nCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Load from multiple sources and merge
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Try legacy YAML file first (base layer)
        Map<String, Object> yamlData = loadFromYaml(locale);
        if (yamlData != null) {
            result.putAll(yamlData);
        }

        // 2. Try compiled JSON file (overrides YAML)
        Map<String, Object> jsonData = loadFromJson(locale);
        if (jsonData != null) {
            result.putAll(jsonData);
        }

        // 3. Try database (highest priority, overrides all)
        Map<String, Object> dbData = loadFromDatabase(locale);
        if (dbData != null) {
            result.putAll(dbData);
        }

        // If no data found, try default locale
        if (result.isEmpty() && !DEFAULT_LOCALE.equals(locale)) {
            log.debug("No i18n data found for locale: {}, falling back to {}", locale, DEFAULT_LOCALE);
            return getI18nData(DEFAULT_LOCALE);
        }

        // Cache the result with tenant-aware key
        i18nCache.put(cacheKey, result);

        return result;
    }

    /**
     * Get a single i18n value by key
     */
    public String getValue(String locale, String key) {
        Map<String, Object> data = getI18nData(locale);
        Object value = data.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Get a single i18n value by key with fallback
     */
    public String getValue(String locale, String key, String fallback) {
        String value = getValue(locale, key);
        return value != null ? value : fallback;
    }

    /**
     * Clear the cache for a specific locale or all locales
     */
    public void clearCache(String locale) {
        if (locale != null) {
            // Invalidate both tenant-specific and public cache entries
            String tenantKey = buildCacheKey(locale);
            i18nCache.invalidate(tenantKey);
            i18nCache.invalidate("0:" + locale);
            log.debug("Cleared i18n cache for locale: {}", locale);
        } else {
            i18nCache.invalidateAll();
            log.debug("Cleared all i18n cache");
        }
    }

    /**
     * Build tenant-aware cache key: "{tenantId}:{locale}"
     * Uses "0" for public (unauthenticated) requests to isolate from tenant-specific caches.
     */
    private String buildCacheKey(String locale) {
        Long tenantId = null;
        try {
            tenantId = MetaContext.getCurrentTenantId();
        } catch (Exception ignored) {
            // No tenant context (e.g., public endpoint)
        }
        long tid = (tenantId != null && tenantId > 0) ? tenantId : 0L;
        return tid + ":" + locale;
    }

    /**
     * Load i18n data from database
     */
    private Map<String, Object> loadFromDatabase(String locale) {
        if (i18nResourceService == null) {
            return null;
        }

        try {
            Map<String, String> dbData = i18nResourceService.getResourceMapByLang(locale);
            if (dbData != null && !dbData.isEmpty()) {
                log.debug("Loaded {} i18n entries from database for locale: {}", dbData.size(), locale);
                return new LinkedHashMap<>(dbData);
            }
        } catch (Exception e) {
            log.warn("Failed to load i18n data from database for locale: {}", locale, e);
        }

        return null;
    }

    /**
     * Load i18n data from compiled JSON file
     */
    private Map<String, Object> loadFromJson(String locale) {
        String resourcePath = "i18n/i18n." + locale + ".json";
        Resource resource = new ClassPathResource(resourcePath);

        if (!resource.exists()) {
            return null;
        }

        try {
            String content = readResourceAsString(resource);
            Map<String, Object> jsonMap = jsonMapper.readValue(content, new TypeReference<Map<String, Object>>() {});

            // Flatten nested map to dot-separated keys
            Map<String, Object> flattenedMap = new LinkedHashMap<>();
            flattenMap(jsonMap, "", flattenedMap);

            log.debug("Loaded {} i18n entries from JSON for locale: {}", flattenedMap.size(), locale);
            return flattenedMap;
        } catch (IOException e) {
            log.warn("Failed to load i18n JSON file for locale: {}", locale, e);
            return null;
        }
    }

    /**
     * Load i18n data from legacy YAML file
     */
    private Map<String, Object> loadFromYaml(String locale) {
        String resourcePath = "i18n." + locale + ".yaml";
        Resource resource = new ClassPathResource(resourcePath);

        if (!resource.exists()) {
            // Try default locale
            if (!DEFAULT_LOCALE.equals(locale)) {
                resource = new ClassPathResource("i18n." + DEFAULT_LOCALE + ".yaml");
                if (!resource.exists()) {
                    return null;
                }
            } else {
                return null;
            }
        }

        try {
            String content = readResourceAsString(resource);
            @SuppressWarnings("unchecked")
            Map<String, Object> yamlMap = yamlMapper.readValue(content, Map.class);

            // Flatten nested map to dot-separated keys
            Map<String, Object> flattenedMap = new LinkedHashMap<>();
            flattenMap(yamlMap, "", flattenedMap);

            log.debug("Loaded {} i18n entries from YAML for locale: {}", flattenedMap.size(), locale);
            return flattenedMap;
        } catch (IOException e) {
            log.warn("Failed to load i18n YAML file for locale: {}", locale, e);
            return null;
        }
    }

    /**
     * Flatten a nested map to dot-separated keys
     *
     * @param nestedMap The nested map to flatten
     * @param prefix Current key prefix
     * @param flattenedMap Result map
     */
    @SuppressWarnings("unchecked")
    private void flattenMap(Map<String, Object> nestedMap, String prefix, Map<String, Object> flattenedMap) {
        if (nestedMap == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : nestedMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            String newKey = prefix.isEmpty() ? key : prefix + "." + key;

            if (value instanceof Map) {
                // Recursively flatten nested maps
                flattenMap((Map<String, Object>) value, newKey, flattenedMap);
            } else if (value instanceof Iterable && !(value instanceof String)) {
                // Handle collections
                int index = 0;
                for (Object item : (Iterable<?>) value) {
                    String indexKey = newKey + "[" + index + "]";
                    if (item instanceof Map) {
                        flattenMap((Map<String, Object>) item, indexKey, flattenedMap);
                    } else {
                        flattenedMap.put(indexKey, item);
                    }
                    index++;
                }
            } else {
                // Add leaf node
                flattenedMap.put(newKey, value);
            }
        }
    }

    private String readResourceAsString(Resource resource) throws IOException {
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
    }
}
