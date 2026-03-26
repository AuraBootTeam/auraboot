package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 模型元数据Bean
 * 用于DictEntity的modelMeta字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelPayload {
    
    /**
     * 表名
     */
    private String tableName;
    
    /**
     * 主键字段
     */
    private String primaryKey;
    
    /**
     * 索引配置
     */
    private List<IndexConfig> indexes;
    
    /**
     * 约束配置
     */
    private List<ConstraintConfig> constraints;
    
    /**
     * 分区配置
     */
    private PartitionConfig partition;
    
    /**
     * 存储引擎
     */
    private String engine;
    
    /**
     * 字符集
     */
    private String charset;
    
    /**
     * 排序规则
     */
    private String collation;
    
    /**
     * 表注释
     */
    private String comment;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    /**
     * 索引配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IndexConfig {
        private String name;
        private List<String> columns;
        private String type; // UNIQUE, NORMAL, FULLTEXT
        private String method; // BTREE, HASH
    }
    
    /**
     * 约束配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConstraintConfig {
        private String name;
        private String type; // PRIMARY_KEY, FOREIGN_KEY, UNIQUE, CHECK
        private List<String> columns;
        private String referenceTable;
        private List<String> referenceColumns;
        private String onDelete; // CASCADE, SET_NULL, RESTRICT
        private String onUpdate; // CASCADE, SET_NULL, RESTRICT
    }
    
    /**
     * 分区配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PartitionConfig {
        private String type; // RANGE, LIST, HASH
        private String column;
        private List<PartitionDefinition> partitions;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class PartitionDefinition {
            private String name;
            private String value;
            private String operator; // LESS_THAN, IN, MODULO
        }
    }
}