package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.Map;

/**
 * 字典项统计信息DTO
 * 用于字典项统计数据的返回
 */
@Data
public class DictItemStatistics {

    /**
     * 总数量
     */
    private Integer totalCount;

    /**
     * 启用数量
     */
    private Integer enabledCount;

    /**
     * 禁用数量
     */
    private Integer disabledCount;

    /**
     * 默认值数量
     */
    private Integer defaultCount;

    /**
     * 级联层级统计（级联字典使用）
     */
    private Map<Integer, Integer> levelStats;

    /**
     * 父级值统计（级联字典使用）
     */
    private Map<String, Integer> parentStats;

    /**
     * 最大排序号
     */
    private Integer maxSortNo;

    /**
     * 最小排序号
     */
    private Integer minSortNo;

    /**
     * 统计时间戳
     */
    private Long statisticsTimestamp;

    /**
     * 构造函数
     */
    public DictItemStatistics() {
        this.totalCount = 0;
        this.enabledCount = 0;
        this.disabledCount = 0;
        this.defaultCount = 0;
        this.maxSortNo = 0;
        this.minSortNo = 0;
        this.statisticsTimestamp = System.currentTimeMillis();
    }
}