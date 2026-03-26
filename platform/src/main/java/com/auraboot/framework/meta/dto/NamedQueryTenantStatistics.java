package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 命名查询租户统计DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryTenantStatistics {

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 统计时间
     */
    private LocalDateTime statisticsTime;

    /**
     * 统计时间范围开始
     */
    private LocalDateTime startTime;

    /**
     * 统计时间范围结束
     */
    private LocalDateTime endTime;

    /**
     * 查询总数
     */
    private Integer totalQueries;

    /**
     * 启用查询数
     */
    private Integer enabledQueries;

    /**
     * 禁用查询数
     */
    private Integer disabledQueries;

    /**
     * 复杂查询数
     */
    private Integer complexQueries;

    /**
     * 简单查询数
     */
    private Integer simpleQueries;

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
     * 成功率
     */
    private Double successRate;

    /**
     * 活跃用户数
     */
    private Integer activeUsers;

    /**
     * 按状态分组统计
     */
    private Map<String, Integer> queriesByStatus;

    /**
     * 按类型分组统计
     */
    private Map<String, Integer> queriesByType;

    /**
     * 热门查询列表
     */
    private List<PopularQuery> popularQueries;

    /**
     * 性能最差查询列表
     */
    private List<SlowQuery> slowQueries;

    /**
     * 错误最多查询列表
     */
    private List<ErrorProneQuery> errorProneQueries;

    /**
     * 使用趋势数据
     */
    private List<UsageTrend> usageTrends;

    /**
     * 资源使用统计
     */
    private ResourceUsageStats resourceUsage;

    /**
     * 性能指标
     */
    private PerformanceMetrics performanceMetrics;

    /**
     * 热门查询内部类
     */
    @Data
    public static class PopularQuery {
        private Long queryId;
        private String queryCode;
        private String queryTitle;
        private Long executionCount;
        private Double averageExecutionTime;
        private LocalDateTime lastExecuted;
        private Integer uniqueUsers;
    }

    /**
     * 慢查询内部类
     */
    @Data
    public static class SlowQuery {
        private Long queryId;
        private String queryCode;
        private String queryTitle;
        private Double averageExecutionTime;
        private Long maxExecutionTime;
        private Long executionCount;
        private String performanceGrade;
    }

    /**
     * 错误频发查询内部类
     */
    @Data
    public static class ErrorProneQuery {
        private Long queryId;
        private String queryCode;
        private String queryTitle;
        private Long errorCount;
        private Double errorRate;
        private String mostCommonError;
        private LocalDateTime lastError;
    }

    /**
     * 使用趋势内部类
     */
    @Data
    public static class UsageTrend {
        private LocalDateTime timestamp;
        private Long executionCount;
        private Integer activeQueries;
        private Integer activeUsers;
        private Double averageResponseTime;
        private Double successRate;
    }

    /**
     * 资源使用统计内部类
     */
    @Data
    public static class ResourceUsageStats {
        private Long totalCpuTimeMs;
        private Long totalMemoryUsedBytes;
        private Long totalDiskIoBytes;
        private Long totalNetworkIoBytes;
        private Integer peakConcurrentQueries;
        private Double averageConcurrency;
        private String resourceEfficiency; // EXCELLENT, GOOD, FAIR, POOR
    }

    /**
     * 性能指标内部类
     */
    @Data
    public static class PerformanceMetrics {
        private Double p50ResponseTime;
        private Double p95ResponseTime;
        private Double p99ResponseTime;
        private Double throughputQps;
        private Double cacheHitRatio;
        private Integer connectionPoolUtilization;
        private String overallPerformanceGrade; // EXCELLENT, GOOD, FAIR, POOR
    }

    public NamedQueryTenantStatistics() {
        this.statisticsTime = LocalDateTime.now(ZoneOffset.UTC);
    }
}