package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 字段索引提示配置Bean
 * 用于FieldEntity的indexHint字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldIndexHintBean {
    
    /**
     * 是否建议创建索引
     */
    private Boolean suggestIndex;
    
    /**
     * 索引类型 (btree, hash, gin, gist等)
     */
    private String indexType;
    
    /**
     * 索引名称
     */
    private String indexName;
    
    /**
     * 是否唯一索引
     */
    private Boolean unique;
    
    /**
     * 是否部分索引
     */
    private Boolean partial;
    
    /**
     * 部分索引条件
     */
    private String partialCondition;
    
    /**
     * 复合索引配置
     */
    private CompositeIndexConfig compositeIndex;
    
    /**
     * 索引优先级 (1-10, 数字越大优先级越高)
     */
    private Integer priority;
    
    /**
     * 预估查询频率 (low, medium, high)
     */
    private String queryFrequency;
    
    /**
     * 预估数据量
     */
    private Long estimatedRows;
    
    /**
     * 索引维护成本评估 (low, medium, high)
     */
    private String maintenanceCost;
    
    /**
     * 性能提升预期 (low, medium, high)
     */
    private String performanceGain;
    
    /**
     * 索引统计信息
     */
    private IndexStats stats;
    
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
    public static class CompositeIndexConfig {
        private List<String> fields;
        private List<String> sortOrders; // ASC, DESC
        private String indexName;
        private Boolean includeFields; // 是否包含其他字段
        private List<String> includedFields;
        private Map<String, Object> options;
    }
    
    /**
     * 索引统计信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IndexStats {
        private Long indexSize; // 索引大小(字节)
        private Double selectivity; // 选择性(0-1)
        private Long scanCount; // 扫描次数
        private Long tupleRead; // 读取元组数
        private Long tuplesFetched; // 获取元组数
        private String lastAnalyzed; // 最后分析时间
        private Map<String, Object> additionalStats;
    }
}