package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 模型查询统计DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ModelQueryStatistics {

    /**
     * 模型编码
     */
    private String modelCode;

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
     * 访问用户数量
     */
    private Long accessUserCount;

    /**
     * 总返回记录数
     */
    private Long totalRecordsReturned;

    /**
     * 最后查询时间
     */
    private LocalDateTime lastQueryTime;

    /**
     * 最活跃的查询时间段
     */
    private Integer mostActiveHour;

    /**
     * 按查询类型统计
     */
    private Map<String, Long> queryTypeStatistics;

    /**
     * 按用户统计
     */
    private Map<Long, Long> userStatistics;

    /**
     * 按日期统计
     */
    private Map<String, Long> dailyStatistics;

    /**
     * 按小时统计
     */
    private Map<Integer, Long> hourlyStatistics;

    /**
     * 错误类型统计
     */
    private Map<String, Long> errorTypeStatistics;

    /**
     * 热门查询条件
     */
    private Map<String, Long> popularQueryConditions;

    /**
     * 热门选择字段
     */
    private Map<String, Long> popularSelectFields;

    /**
     * 热门排序字段
     */
    private Map<String, Long> popularSortFields;

    /**
     * 最近查询记录
     */
    private List<QueryAuditLogDTO> recentQueries;

    /**
     * 慢查询记录
     */
    private List<QueryAuditLogDTO> slowQueries;

    /**
     * 失败查询记录
     */
    private List<QueryAuditLogDTO> failedQueryRecords;

    /**
     * 查询性能趋势
     */
    private List<QueryPerformanceTrend> performanceTrend;

    /**
     * 查询性能趋势数据
     */
    @Data
    public static class QueryPerformanceTrend {
        private LocalDateTime timestamp;
        private Long queryCount;
        private Double averageExecutionTime;
        private Long errorCount;
        private Double cacheHitRate;
    }
}