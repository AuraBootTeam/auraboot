package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 查询审计统计DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAuditStatistics {

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 统计开始时间
     */
    private LocalDateTime startTime;

    /**
     * 统计结束时间
     */
    private LocalDateTime endTime;

    /**
     * 总查询次数
     */
    private Long totalQueries;

    /**
     * 成功查询次数
     */
    private Long successfulQueries;

    /**
     * 失败查询次数
     */
    private Long failedQueries;

    /**
     * 成功率(%)
     */
    private Double successRate;

    /**
     * 平均执行时间(毫秒)
     */
    private Double averageExecutionTime;

    /**
     * 最大执行时间(毫秒)
     */
    private Integer maxExecutionTime;

    /**
     * 最小执行时间(毫秒)
     */
    private Integer minExecutionTime;

    /**
     * 慢查询次数
     */
    private Long slowQueryCount;

    /**
     * 慢查询阈值(毫秒)
     */
    private Integer slowQueryThreshold;

    /**
     * 缓存命中次数
     */
    private Long cacheHitCount;

    /**
     * 缓存命中率(%)
     */
    private Double cacheHitRate;

    /**
     * 数据脱敏应用次数
     */
    private Long dataMaskingCount;

    /**
     * 安全违规次数
     */
    private Long securityViolationCount;

    /**
     * 唯一用户数
     */
    private Long uniqueUserCount;

    /**
     * 唯一模型数
     */
    private Long uniqueModelCount;

    /**
     * 总返回记录数
     */
    private Long totalRecordsReturned;

    /**
     * 按查询类型统计
     */
    private Map<String, Long> queryTypeStatistics;

    /**
     * 按模型统计
     */
    private Map<String, Long> modelStatistics;

    /**
     * 按用户统计
     */
    private Map<Long, Long> userStatistics;

    /**
     * 按小时统计
     */
    private Map<Integer, Long> hourlyStatistics;

    /**
     * 按日期统计
     */
    private Map<String, Long> dailyStatistics;

    /**
     * 错误类型统计
     */
    private Map<String, Long> errorTypeStatistics;

    /**
     * 执行时间分布
     */
    private Map<String, Long> executionTimeDistribution;

    /**
     * 热门查询条件
     */
    private List<QueryConditionStatistic> popularQueryConditions;

    /**
     * 性能趋势数据
     */
    private List<PerformanceTrendData> performanceTrend;

    /**
     * 查询条件统计
     */
    @Data
    public static class QueryConditionStatistic {
        private String condition;
        private Long count;
        private Double averageExecutionTime;
    }

    /**
     * 性能趋势数据
     */
    @Data
    public static class PerformanceTrendData {
        private LocalDateTime timestamp;
        private Long queryCount;
        private Double averageExecutionTime;
        private Double successRate;
        private Double cacheHitRate;
    }
}