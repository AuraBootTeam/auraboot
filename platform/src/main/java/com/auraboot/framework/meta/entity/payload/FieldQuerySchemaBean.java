package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 字段查询模式配置Bean
 * 用于FieldEntity的querySchema字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldQuerySchemaBean {
    
    /**
     * 是否可查询
     */
    private Boolean queryable;
    
    /**
     * 是否可排序
     */
    private Boolean sortable;
    
    /**
     * 是否可过滤
     */
    private Boolean filterable;
    
    /**
     * 是否可搜索
     */
    private Boolean searchable;
    
    /**
     * 查询操作符配置
     */
    private List<String> operators;
    
    /**
     * 默认查询操作符
     */
    private String defaultOperator;
    
    /**
     * 查询权重 (用于全文搜索)
     */
    private Double searchWeight;
    
    /**
     * 索引配置
     */
    private IndexConfig indexConfig;
    
    /**
     * 聚合配置
     */
    private AggregationConfig aggregation;
    
    /**
     * 关联查询配置
     */
    private JoinConfig joinConfig;
    
    /**
     * 查询优化配置
     */
    private OptimizationConfig optimization;
    
    /**
     * 缓存配置
     */
    private CacheConfig cacheConfig;
    
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
        private String indexType; // btree, hash, gin, gist
        private Boolean useIndex;
        private String indexName;
        private List<String> compositeFields;
        private String indexMethod;
        private Map<String, Object> indexOptions;
    }
    
    /**
     * 聚合配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AggregationConfig {
        private Boolean allowSum;
        private Boolean allowAvg;
        private Boolean allowCount;
        private Boolean allowMin;
        private Boolean allowMax;
        private Boolean allowGroupBy;
        private List<String> customAggregations;
        private Map<String, Object> aggregationOptions;
    }
    
    /**
     * 关联查询配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JoinConfig {
        private Boolean allowJoin;
        private List<JoinRelation> relations;
        private String defaultJoinType; // INNER, LEFT, RIGHT, FULL
        private Integer maxJoinDepth;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class JoinRelation {
            private String targetEntity;
            private String targetField;
            private String joinType;
            private String alias;
            private String condition;
            private Map<String, Object> options;
        }
    }
    
    /**
     * 查询优化配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OptimizationConfig {
        private Boolean usePartition;
        private String partitionStrategy;
        private Boolean enableQueryPlan;
        private Integer queryTimeout; // 查询超时时间(秒)
        private Integer maxResultSize; // 最大结果集大小
        private Boolean enablePagination;
        private Integer defaultPageSize;
        private Map<String, Object> hints; // 查询提示
    }
    
    /**
     * 缓存配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CacheConfig {
        private Boolean enableCache;
        private Integer cacheTtl; // 缓存过期时间(秒)
        private String cacheKey; // 缓存键模板
        private String cacheLevel; // field, entity, query
        private Boolean enableDistributedCache;
        private Map<String, Object> cacheOptions;
    }
}