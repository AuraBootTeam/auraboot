package com.auraboot.framework.meta.bean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 索引提示Bean
 * 用于DictField的indexHint字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IndexHintBean {
    
    /**
     * 索引类型
     * 如：btree, hash, gin, gist等
     */
    private String indexType;
    
    /**
     * 是否唯一索引
     */
    private Boolean unique;
    
    /**
     * 是否部分索引
     */
    private Boolean partial;
    
    /**
     * 索引条件
     */
    private String condition;
    
    /**
     * 复合索引配置
     */
    private CompositeConfig composite;
    
    /**
     * 性能提示
     */
    private PerformanceHint performance;
    
    /**
     * 索引选项
     */
    private Map<String, Object> options;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    /**
     * 复合索引配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CompositeConfig {
        /**
         * 参与复合索引的字段
         */
        private List<String> fields;
        
        /**
         * 字段顺序
         */
        private List<Integer> order;
        
        /**
         * 排序方向
         */
        private List<String> direction;
        
        /**
         * 索引名称
         */
        private String name;
    }
    
    /**
     * 性能提示
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PerformanceHint {
        /**
         * 预期查询频率
         */
        private String queryFrequency;
        
        /**
         * 数据选择性
         */
        private Double selectivity;
        
        /**
         * 索引大小估算
         */
        private String sizeEstimate;
        
        /**
         * 维护成本
         */
        private String maintenanceCost;
        
        /**
         * 推荐优先级
         */
        private Integer priority;
    }
}