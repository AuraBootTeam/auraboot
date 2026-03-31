package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 字段特性配置Bean
 * 用于FieldEntity的feature字段和Menu的extension字段
 *
 * 支持两种 JSON 格式:
 * 1. 嵌套格式: {"extension": {"key": "value"}}
 * 2. 扁平格式: {"key": "value"} - 通过 @JsonAnySetter 捕获
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExtensionBean {

    /**
     * 扩展属性 (嵌套格式)
     */
    private Map<String, Object> extension;

    /**
     * 动态属性 (扁平格式) - 捕获所有未映射的顶层属性
     */
    private Map<String, Object> dynamicProperties = new HashMap<>();

    @JsonAnySetter
    public void setDynamicProperty(String key, Object value) {
        dynamicProperties.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getDynamicProperties() {
        return dynamicProperties;
    }

    /**
     * 获取属性值，优先从 extension 获取，其次从 dynamicProperties 获取
     */
    public Object get(String key) {
        if (extension != null && extension.containsKey(key)) {
            return extension.get(key);
        }
        return dynamicProperties.get(key);
    }

    /**
     * 获取 modelCode (便捷方法)
     */
    public String getModelCode() {
        Object value = get("modelCode");
        return value != null ? value.toString() : null;
    }

    /**
     * 获取 pageType (便捷方法)
     */
    public String getPageType() {
        Object value = get("pageType");
        return value != null ? value.toString() : null;
    }

    // ==================== Validation ====================

    /** Max number of top-level keys allowed in extension */
    private static final int MAX_KEYS = 50;

    /** Known extension keys for model entities */
    private static final Set<String> MODEL_KNOWN_KEYS = Set.of(
            "displayName", "description", "modelType", "modelCategory", "icon", "category",
            "tableName", "modelCode", "pageType", "schemaType",
            "uiSchema", "querySchema", "tags", "metadata",
            "enableNba");

    /**
     * Validate extension content: key count limit and value size limit.
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        int totalKeys = 0;
        if (extension != null) {
            totalKeys += extension.size();
        }
        totalKeys += dynamicProperties.size();

        if (totalKeys > MAX_KEYS) {
            throw new IllegalArgumentException(
                    "Extension has too many keys (" + totalKeys + "), max allowed: " + MAX_KEYS);
        }
    }
}