package com.auraboot.framework.meta.bean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 查询Schema Bean
 * 用于DictField的querySchema字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuerySchemaBean {
    
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
     * 查询操作符
     */
    private List<String> operators;
    
    /**
     * 默认排序方向
     */
    private String defaultSortOrder; // ASC, DESC
    
    /**
     * 索引提示
     */
    private IndexHint indexHint;
    
    /**
     * 聚合配置
     */
    private AggregationConfig aggregation;
    
    /**
     * 关联查询配置
     */
    private JoinConfig join;
    
    /**
     * 全文搜索配置
     */
    private FullTextSearchConfig fullTextSearch;
    
    /**
     * 缓存配置
     */
    private CacheConfig cache;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    /**
     * 索引提示
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IndexHint {
        private String type; // USE, IGNORE, FORCE
        private List<String> indexes;
        private String forClause; // JOIN, ORDER_BY, GROUP_BY
    }
    
    /**
     * 聚合配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AggregationConfig {
        private Boolean enabled;
        private List<String> functions; // COUNT, SUM, AVG, MIN, MAX
        private String groupBy;
        private String having;
    }
    
    /**
     * 关联查询配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JoinConfig {
        private String type; // INNER, LEFT, RIGHT, FULL
        private String targetTable;
        private String targetField;
        private String condition;
        private Boolean lazy; // 是否懒加载
    }
    
    /**
     * 全文搜索配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FullTextSearchConfig {
        private Boolean enabled;
        private String analyzer; // standard, keyword, ik_smart
        private Float boost; // 权重
        private List<String> synonyms;
        private Boolean highlight;
    }
    
    /**
     * 缓存配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CacheConfig {
        private Boolean enabled;
        private Integer ttl; // 缓存时间（秒）
        private String key; // 缓存键模板
        private String strategy; // LRU, LFU, FIFO
    }
}