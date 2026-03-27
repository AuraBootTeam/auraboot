package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 字段引用目标配置Bean
 * 用于FieldEntity的refTarget字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldRefTargetBean {
    
    /**
     * 引用类型 (entity, table, api, enum等)
     */
    private String refType;
    
    /**
     * 目标实体名称
     */
    @JsonAlias("targetModel")
    private String targetEntity;
    
    /**
     * 目标表名
     */
    private String targetTable;
    
    /**
     * 目标字段名
     */
    private String targetField;
    
    /**
     * 显示字段名
     */
    private String displayField;
    
    /**
     * 值字段名
     */
    private String valueField;
    
    /**
     * API配置
     */
    private ApiConfig apiConfig;
    
    /**
     * 枚举配置
     */
    private EnumConfig enumConfig;
    
    /**
     * 查询条件
     */
    private QueryCondition queryCondition;
    
    /**
     * 级联配置
     */
    private CascadeConfig cascadeConfig;

    /**
     * Bidirectional relation configuration
     */
    private BidirectionalConfig bidirectional;

    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    /**
     * API配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiConfig {
        private String url;
        private String method; // GET, POST等
        private Map<String, String> headers;
        private Map<String, Object> params;
        private String dataPath; // 响应数据路径
        private Integer timeout;
        private Boolean enableCache;
        private Integer cacheTtl;
    }
    
    /**
     * 枚举配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EnumConfig {
        private String enumClass;
        private List<EnumItem> items;
        private Boolean allowCustom;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class EnumItem {
            private Object value;
            private String label;
            private String description;
            private Boolean disabled;
            private String color;
            private String icon;
            private Map<String, Object> metadata;
        }
    }
    
    /**
     * 查询条件
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueryCondition {
        private String whereClause;
        private Map<String, Object> parameters;
        private String orderBy;
        private Integer limit;
        private List<String> groupBy;
        private String having;
        private Boolean distinct;
    }
    
    /**
     * 级联配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CascadeConfig {
        private Boolean enableCascade;
        private String parentField;
        private String childField;
        private List<CascadeLevel> levels;
        private Boolean lazyLoad;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class CascadeLevel {
            private Integer level;
            private String entity;
            private String parentField;
            private String valueField;
            private String displayField;
            private QueryCondition condition;
        }
    }

    /**
     * Bidirectional relation configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BidirectionalConfig {
        /**
         * Whether this is the owning side of the relation
         */
        private Boolean isOwningSide;

        /**
         * The inverse field code on the target model
         * e.g., if Order.customer -> Customer, then Customer.orders is the inverse
         */
        private String inverseFieldCode;

        /**
         * Relation type: ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY
         */
        private String relationType;

        /**
         * For MANY_TO_MANY: junction table name
         */
        private String junctionTable;

        /**
         * For MANY_TO_MANY: this side's FK column in junction table
         */
        private String junctionSourceColumn;

        /**
         * For MANY_TO_MANY: target side's FK column in junction table
         */
        private String junctionTargetColumn;

        /**
         * Cascade operations: NONE, ALL, PERSIST, MERGE, REMOVE
         */
        private List<String> cascadeOperations;

        /**
         * Whether to fetch eagerly or lazily
         */
        private Boolean lazyFetch;
    }
}