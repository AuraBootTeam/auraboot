package com.auraboot.framework.i18n.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 国际化文本处理器
 * 用于处理DSL Schema中的多语言Map对象，根据locale转换为对应的字符串
 * 
 * @author AuraBoot
 */
@Slf4j
@Component
public class I18nTextProcessor {

    /**
     * 处理DSL Schema中的国际化文本
     * 
     * @param schema DSL Schema对象
     * @param locale 目标语言
     * @return 处理后的Schema对象
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> processI18nTexts(Map<String, Object> schema, String locale) {
        if (schema == null || locale == null) {
            return schema;
        }

        log.debug("开始处理国际化文本，目标语言: {}", locale);
        
        Map<String, Object> processedSchema = schema;
        
        // 递归处理所有字段
        processMapRecursively(processedSchema, locale);
        
        return processedSchema;
    }

    /**
     * 递归处理Map中的所有字段
     * 
     * @param map 要处理的Map
     * @param locale 目标语言
     */
    @SuppressWarnings("unchecked")
    private void processMapRecursively(Map<String, Object> map, String locale) {
        if (map == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                Map<String, Object> mapValue = (Map<String, Object>) value;
                
                // 只处理特定字段的国际化文本
                if (shouldProcessI18nField(key) && isI18nTextObject(mapValue)) {
                    // 转换为本地化文本
                    String localizedText = getLocalizedText(mapValue, locale);
                    map.put(key, localizedText);
                    log.debug("转换国际化字段 {}: {} -> {}", key, mapValue, localizedText);
                } else {
                    // 递归处理嵌套Map
                    processMapRecursively(mapValue, locale);
                }
            } else if (value instanceof List) {
                // 处理List中的元素
                processListRecursively((List<Object>) value, locale);
            }
        }
    }

    /**
     * 递归处理List中的所有元素
     * 
     * @param list 要处理的List
     * @param locale 目标语言
     */
    @SuppressWarnings("unchecked")
    private void processListRecursively(List<Object> list, String locale) {
        if (list == null) {
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            
            if (item instanceof Map) {
                Map<String, Object> mapItem = (Map<String, Object>) item;
                
                if (isI18nTextObject(mapItem)) {
                    // 转换为本地化文本
                    String localizedText = getLocalizedText(mapItem, locale);
                    list.set(i, localizedText);
                    log.debug("转换List中的国际化对象: {} -> {}", mapItem, localizedText);
                } else {
                    // 递归处理嵌套Map
                    processMapRecursively(mapItem, locale);
                }
            } else if (item instanceof List) {
                // 递归处理嵌套List
                processListRecursively((List<Object>) item, locale);
            }
        }
    }

    /**
     * 检查字段是否需要进行国际化处理
     * 只处理特定的字段：label、confirm、title、placeholder、message
     * 
     * @param fieldName 字段名
     * @return 是否需要处理
     */
    private boolean shouldProcessI18nField(String fieldName) {
        return "label".equals(fieldName) || 
               "confirm".equals(fieldName) || 
               "title".equals(fieldName) || 
               "placeholder".equals(fieldName) || 
               "message".equals(fieldName);
    }

    /**
     * 检查是否是国际化文本对象
     * 判断标准：Map的所有key都是语言代码（如zh-CN, en-US等）
     * 
     * @param map 要检查的Map
     * @return 是否是国际化文本对象
     */
    private boolean isI18nTextObject(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return false;
        }

        // 检查是否包含常见的语言代码
        Set<String> keys = map.keySet();
        for (String key : keys) {
            if (isLanguageCode(key)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 判断是否是语言代码
     * 
     * @param key 键名
     * @return 是否是语言代码
     */
    private boolean isLanguageCode(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        
        // 常见的语言代码格式：zh-CN, en-US, ja-JP, ko-KR等
        return key.matches("^[a-z]{2}-[A-Z]{2}$") || 
               key.matches("^[a-z]{2}$") ||
               key.equals("zh") || key.equals("en") || key.equals("ja") || key.equals("ko");
    }

    /**
     * 从国际化文本对象中获取本地化文本
     * 
     * @param i18nTextMap 国际化文本Map
     * @param locale 目标语言
     * @return 本地化文本
     */
    private String getLocalizedText(Map<String, Object> i18nTextMap, String locale) {
        if (i18nTextMap == null || i18nTextMap.isEmpty()) {
            return "";
        }

        // 1. 优先使用精确匹配的locale
        Object exactMatch = i18nTextMap.get(locale);
        if (exactMatch != null) {
            return exactMatch.toString();
        }

        // 2. 尝试匹配语言代码（如zh-CN -> zh）
        String languageCode = locale.split("-")[0];
        Object languageMatch = i18nTextMap.get(languageCode);
        if (languageMatch != null) {
            return languageMatch.toString();
        }

        // 3. 尝试匹配同语言的其他地区代码
        for (Map.Entry<String, Object> entry : i18nTextMap.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(languageCode + "-")) {
                return entry.getValue().toString();
            }
        }

        // 4. 使用默认语言（中文）
        Object defaultText = i18nTextMap.get("zh-CN");
        if (defaultText != null) {
            return defaultText.toString();
        }

        Object zhText = i18nTextMap.get("zh");
        if (zhText != null) {
            return zhText.toString();
        }

        // 5. 使用英文作为备选
        Object enText = i18nTextMap.get("en-US");
        if (enText != null) {
            return enText.toString();
        }

        Object enTextShort = i18nTextMap.get("en");
        if (enTextShort != null) {
            return enTextShort.toString();
        }

        // 6. 使用第一个可用的值
        for (Object value : i18nTextMap.values()) {
            if (value != null) {
                return value.toString();
            }
        }

        return "";
    }

    /**
     * 深拷贝Map对象
     * 
     * @param original 原始Map
     * @return 深拷贝后的Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyMap(Map<String, Object> original) {
        if (original == null) {
            return null;
        }

        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                copy.put(key, deepCopyMap((Map<String, Object>) value));
            } else if (value instanceof List) {
                copy.put(key, deepCopyList((List<Object>) value));
            } else {
                copy.put(key, value);
            }
        }

        return copy;
    }

    /**
     * 深拷贝List对象
     * 
     * @param original 原始List
     * @return 深拷贝后的List
     */
    @SuppressWarnings("unchecked")
    private List<Object> deepCopyList(List<Object> original) {
        if (original == null) {
            return null;
        }

        List<Object> copy = new ArrayList<>();
        for (Object item : original) {
            if (item instanceof Map) {
                copy.add(deepCopyMap((Map<String, Object>) item));
            } else if (item instanceof List) {
                copy.add(deepCopyList((List<Object>) item));
            } else {
                copy.add(item);
            }
        }

        return copy;
    }
}