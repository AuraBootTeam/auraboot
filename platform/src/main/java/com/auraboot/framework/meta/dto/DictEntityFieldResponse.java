package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据字典实体与字段关联响应DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictEntityFieldResponse extends AbstractResponse {

    /**
     * 关联的数据字典实体ID
     */
    private Long entityId;

    /**
     * 关联的字段定义ID
     */
    private Long fieldId;

    /**
     * 字段在实体中的排序
     */
    private Integer fieldOrder;
    
    /**
     * 实体信息
     */
    private EntityInfo entityInfo;
    
    /**
     * 字段信息
     */
    private FieldInfo fieldInfo;

    /**
     * 实体字段统计响应
     */
    @Data
    public static class EntityFieldStats {
        private Long entityId;
        private String entityCode;
        private String entityName;
        private Integer fieldCount;
        private Integer totalFields;
        private Integer usageCount;
        private java.util.List<DictEntityFieldResponse> fields;
    }

    /**
     * 字段使用统计响应
     */
    @Data
    public static class FieldUsageStats {
        private Long fieldId;
        private String code;
        private String fieldName;
        private Integer usageCount;
        private String lastUsedAt;
        private java.util.List<DictEntityFieldResponse> entities;
    }
    
    /**
     * 实体信息
     */
    @Data
    public static class EntityInfo {
        private Long id;
        private String pid;
        private String code;
        private String name;
        private String description;
        private Integer version;
        private String status;
        private java.util.Map<String, Object> uiMeta;
        private java.util.Map<String, Object> modelMeta;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime updatedAt;
    }
    
    /**
     * 字段信息
     */
    @Data
    public static class FieldInfo {
        private Long id;
        private String pid;
        private String code;
        private String name;
        private String description;
        private String dataType;
        private Boolean required;
        private String defaultValue;
        private Long dataSourceId;
        private java.util.Map<String, Object> feature;
        private java.util.Map<String, Object> refTarget;
        private java.util.Map<String, Object> adhocAttr;
        private String piiClass;
        private java.util.Map<String, Object> indexHint;
        private java.util.Map<String, Object> uiSchema;
        private java.util.Map<String, Object> querySchema;
        private java.util.Map<String, Object> ruleSchema;
        private java.time.LocalDateTime createdAt;
        private java.time.LocalDateTime updatedAt;
    }
}
