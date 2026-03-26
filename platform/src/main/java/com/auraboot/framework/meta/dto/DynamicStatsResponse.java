package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 动态统计数据响应DTO
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
public class DynamicStatsResponse {
    
    /**
     * 总记录数
     */
    private Long totalCount;
    
    /**
     * 今日新增
     */
    private Long todayCount;
    
    /**
     * 本周新增
     */
    private Long weekCount;
    
    /**
     * 本月新增
     */
    private Long monthCount;
    
    /**
     * 最近7天趋势数据
     */
    private List<TrendData> trendData;
    
    /**
     * 分组统计数据
     */
    private List<GroupData> groupData;
    
    /**
     * 扩展统计数据
     */
    private Map<String, Object> customStats;
    
    /**
     * 趋势数据项
     */
    @Data
    public static class TrendData {
        /**
         * 日期
         */
        private String date;
        
        /**
         * 数量
         */
        private Long count;
        
        /**
         * 扩展数据
         */
        private Map<String, Object> extra;
    }
    
    /**
     * 分组数据项
     */
    @Data
    public static class GroupData {
        /**
         * 分组名称
         */
        private String name;
        
        /**
         * 分组值
         */
        private String value;
        
        /**
         * 数量
         */
        private Long count;
        
        /**
         * 百分比
         */
        private Double percentage;
        
        /**
         * 扩展数据
         */
        private Map<String, Object> extra;
    }
}