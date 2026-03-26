package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系统计请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingStatisticsRequest {

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 模型ID（可选，指定模型的统计）
     */
    private Long modelId;

    /**
     * 字段ID（可选，指定字段的统计）
     */
    private Long fieldId;

    /**
     * 统计类型
     */
    private StatisticsType statisticsType;

    /**
     * 是否包含详细信息
     */
    private Boolean includeDetails;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingStatisticsRequest() {
        this.statisticsType = StatisticsType.OVERVIEW;
        this.includeDetails = true;
    }

    /**
     * 统计类型
     */
    public enum StatisticsType {
        /**
         * 概览统计
         */
        OVERVIEW,

        /**
         * 详细统计
         */
        DETAILED,

        /**
         * 趋势统计
         */
        TREND
    }
}