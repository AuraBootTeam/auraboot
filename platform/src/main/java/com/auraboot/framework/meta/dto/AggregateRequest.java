package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 聚合查询请求
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class AggregateRequest {
    
    /**
     * 聚合字段列表
     */
    private List<AggregateField> aggregateFields;
    
    /**
     * 分组字段
     */
    private List<String> groupByFields;
    
    /**
     * 查询条件
     */
    private List<QueryCondition> conditions;
    
    /**
     * 排序字段
     */
    private List<SortField> sortFields;
    
    /**
     * 限制结果数量
     */
    private Integer limit;
    
    /**
     * 扩展参数
     */
    private Map<String, Object> extraParams;
    
    @Data
    @Builder
    public static class AggregateField {
        private String fieldName;
        private AggregateFunction function;
        private String alias;
    }
    
    public enum AggregateFunction {
        COUNT, SUM, AVG, MAX, MIN, DISTINCT_COUNT
    }
}