package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 聚合字段定义
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class AggregateField {
    
    /**
     * 字段名
     */
    private String fieldName;
    
    /**
     * 聚合函数
     */
    private AggregateFunction function;
    
    /**
     * 别名
     */
    private String alias;
    
    /**
     * 聚合函数枚举
     */
    public enum AggregateFunction {
        COUNT,
        SUM,
        AVG,
        MAX,
        MIN,
        COUNT_DISTINCT
    }
}