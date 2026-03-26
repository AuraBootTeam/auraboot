package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.Map;

/**
 * 字典统计信息DTO
 */
@Data
public class DictStatistics {

    /**
     * 总数量
     */
    private Long totalCount;

    /**
     * 启用数量
     */
    private Long enabledCount;

    /**
     * 禁用数量
     */
    private Long disabledCount;

    /**
     * 已发布数量
     */
    private Long publishedCount;

    /**
     * 草稿数量
     */
    private Long draftCount;

    /**
     * 系统字典数量
     */
    private Long systemCount;

    /**
     * 用户字典数量
     */
    private Long userCount;

    /**
     * 按类型分布
     */
    private Map<String, Long> typeDistribution;

    /**
     * 按数据源类型分布
     */
    private Map<String, Long> sourceTypeDistribution;

    /**
     * 按版本策略分布
     */
    private Map<String, Long> versionStrategyDistribution;

    /**
     * 按状态分布
     */
    private Map<String, Long> statusDistribution;

    /**
     * 最近创建数量（7天内）
     */
    private Long recentCreatedCount;

    /**
     * 最近更新数量（7天内）
     */
    private Long recentUpdatedCount;

    /**
     * 平均字典项数量
     */
    private Double averageItemCount;

    /**
     * 最大字典项数量
     */
    private Long maxItemCount;

    /**
     * 最小字典项数量
     */
    private Long minItemCount;
}