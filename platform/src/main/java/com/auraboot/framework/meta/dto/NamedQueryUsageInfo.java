package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 命名查询使用情况信息DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryUsageInfo {

    /**
     * 查询ID
     */
    private Long queryId;

    /**
     * 查询编码
     */
    private String queryCode;

    /**
     * 统计开始时间
     */
    private LocalDateTime startTime;

    /**
     * 统计结束时间
     */
    private LocalDateTime endTime;

    /**
     * 统计粒度
     */
    private String granularity;

    /**
     * 总执行次数
     */
    private Long totalExecutions;

    /**
     * 成功执行次数
     */
    private Long successfulExecutions;

    /**
     * 失败执行次数
     */
    private Long failedExecutions;

    /**
     * 平均执行时间（毫秒）
     */
    private Double averageExecutionTime;

    /**
     * 最大执行时间（毫秒）
     */
    private Long maxExecutionTime;

    /**
     * 最小执行时间（毫秒）
     */
    private Long minExecutionTime;

    /**
     * 成功率
     */
    private Double successRate;

    /**
     * 使用用户数
     */
    private Integer uniqueUsers;

    /**
     * 使用环境数
     */
    private Integer uniqueEnvironments;

    /**
     * 时间序列数据
     */
    private List<UsageTimePoint> timeSeriesData;

    /**
     * 用户使用统计
     */
    private List<UserUsageStats> userUsageStats;

    /**
     * 环境使用统计
     */
    private List<EnvironmentUsageStats> environmentUsageStats;

    /**
     * 错误统计
     */
    private List<ErrorStats> errorStats;

    /**
     * 性能趋势
     */
    private List<PerformanceTrend> performanceTrends;

    /**
     * 使用热点时间
     */
    private List<String> peakUsageHours;

    /**
     * 使用模式分析
     */
    private UsagePatternAnalysis usagePatternAnalysis;

    /**
     * 使用时间点内部类
     */
    @Data
    public static class UsageTimePoint {
        private LocalDateTime timestamp;
        private Long executionCount;
        private Long successCount;
        private Long failureCount;
        private Double averageResponseTime;
        private Integer concurrentUsers;
    }

    /**
     * 用户使用统计内部类
     */
    @Data
    public static class UserUsageStats {
        private Long userId;
        private String username;
        private Long executionCount;
        private Double averageResponseTime;
        private LocalDateTime lastUsedAt;
        private String usagePattern; // HEAVY, MODERATE, LIGHT, OCCASIONAL
    }

    /**
     * 环境使用统计内部类
     */
    @Data
    public static class EnvironmentUsageStats {
        private String environment;
        private Long executionCount;
        private Double averageResponseTime;
        private Double successRate;
        private LocalDateTime lastUsedAt;
    }

    /**
     * 错误统计内部类
     */
    @Data
    public static class ErrorStats {
        private String errorType;
        private String errorMessage;
        private Long errorCount;
        private Double errorRate;
        private LocalDateTime firstOccurred;
        private LocalDateTime lastOccurred;
    }

    /**
     * 性能趋势内部类
     */
    @Data
    public static class PerformanceTrend {
        private LocalDateTime timestamp;
        private Double averageResponseTime;
        private Double p95ResponseTime;
        private Double p99ResponseTime;
        private Long throughput;
        private String performanceGrade;
    }

    /**
     * 使用模式分析内部类
     */
    @Data
    public static class UsagePatternAnalysis {
        private String primaryUsagePattern; // BATCH, INTERACTIVE, SCHEDULED, MIXED
        private Map<String, Integer> hourlyDistribution;
        private Map<String, Integer> dailyDistribution;
        private List<String> commonParameters;
        private String usageFrequency; // HIGH, MEDIUM, LOW
        private String usageStability; // STABLE, FLUCTUATING, DECLINING, GROWING
    }
}