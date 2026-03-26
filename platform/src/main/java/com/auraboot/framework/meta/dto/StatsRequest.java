package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 统计请求
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class StatsRequest {
    
    /**
     * 统计类型列表
     */
    private List<StatsType> statsTypes;
    
    /**
     * 分组字段
     */
    private List<String> groupByFields;
    
    /**
     * 过滤条件
     */
    private List<QueryCondition> conditions;
    
    /**
     * 额外参数
     */
    private Map<String, Object> extraParams;
    
    /**
     * 统计类型枚举
     */
    public enum StatsType {
        COUNT,      // 计数
        SUM,        // 求和
        AVG,        // 平均值
        MAX,        // 最大值
        MIN,        // 最小值
        DISTINCT    // 去重计数
    }
}