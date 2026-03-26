package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 查询性能统计DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryPerformanceStatistics {

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
     * 平均执行时间(毫秒)
     */
    private Double averageExecutionTime;

    /**
     * 中位数执行时间(毫秒)
     */
    private Double medianExecutionTime;

    /**
     * 95百分位执行时间(毫秒)
     */
    private Double p95ExecutionTime;

    /**
     * 99百分位执行时间(毫秒)
     */
    private Double p99ExecutionTime;

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
     * 慢查询比例(%)
     */
    private Double slowQueryRate;

    /**
     * 超时查询次数
     */
    private Long timeoutQueryCount;

    /**
     * 缓存命中次数
     */
    private Long cacheHitCount;

    /**
     * 缓存未命中次数
     */
    private Long cacheMissCount;

    /**
     * 缓存命中率(%)
     */
    private Double cacheHitRate;

    /**
     * 平均权限检查时间(毫秒)
     */
    private Double averagePermissionCheckTime;

    /**
     * 平均安全验证时间(毫秒)
     */
    private Double averageSecurityValidationTime;

    /**
     * 数据脱敏应用次数
     */
    private Long dataMaskingCount;

    /**
     * 并发查询峰值
     */
    private Integer peakConcurrentQueries;

    /**
     * 平均并发查询数
     */
    private Double averageConcurrentQueries;

    /**
     * 查询吞吐量(QPS)
     */
    private Double queryThroughput;

    /**
     * 错误率(%)
     */
    private Double errorRate;

    /**
     * 按执行时间区间统计
     */
    private Map<String, Long> executionTimeDistribution;

    /**
     * 按查询类型性能统计
     */
    private Map<String, QueryTypePerformance> queryTypePerformance;

    /**
     * 按模型性能统计
     */
    private Map<String, ModelPerformance> modelPerformance;

    /**
     * 按用户性能统计
     */
    private Map<Long, UserPerformance> userPerformance;

    /**
     * 性能趋势数据
     */
    private List<PerformanceTrendPoint> performanceTrend;

    /**
     * 慢查询详情
     */
    private List<SlowQueryDetail> slowQueries;

    /**
     * 性能瓶颈分析
     */
    private List<PerformanceBottleneck> performanceBottlenecks;

    /**
     * 性能优化建议
     */
    private List<PerformanceOptimizationSuggestion> optimizationSuggestions;

    /**
     * 查询类型性能统计
     */
    @Data
    public static class QueryTypePerformance {
        private String queryType;
        private Long queryCount;
        private Double averageExecutionTime;
        private Double p95ExecutionTime;
        private Long slowQueryCount;
        private Double cacheHitRate;
        private Double errorRate;
    }

    /**
     * 模型性能统计
     */
    @Data
    public static class ModelPerformance {
        private String modelCode;
        private Long queryCount;
        private Double averageExecutionTime;
        private Double p95ExecutionTime;
        private Long slowQueryCount;
        private Double cacheHitRate;
        private Double errorRate;
    }

    /**
     * 用户性能统计
     */
    @Data
    public static class UserPerformance {
        private Long userId;
        private Long queryCount;
        private Double averageExecutionTime;
        private Long slowQueryCount;
        private Double cacheHitRate;
        private Double errorRate;
    }

    /**
     * 性能趋势点
     */
    @Data
    public static class PerformanceTrendPoint {
        private LocalDateTime timestamp;
        private Long queryCount;
        private Double averageExecutionTime;
        private Double p95ExecutionTime;
        private Double cacheHitRate;
        private Double errorRate;
        private Integer concurrentQueries;
    }

    /**
     * 慢查询详情
     */
    @Data
    public static class SlowQueryDetail {
        private String queryId;
        private String modelCode;
        private Long userId;
        private Integer executionTime;
        private String queryConditions;
        private LocalDateTime createdAt;
        private String optimizationSuggestion;
    }

    /**
     * 性能瓶颈
     */
    @Data
    public static class PerformanceBottleneck {
        private String bottleneckType;
        private String description;
        private String affectedComponent;
        private Double impactScore;
        private String recommendation;
    }

    /**
     * 性能优化建议
     */
    @Data
    public static class PerformanceOptimizationSuggestion {
        private String suggestionType;
        private String description;
        private String targetComponent;
        private Double expectedImprovement;
        private String implementationComplexity;
        private Integer priority;
    }
}