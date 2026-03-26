package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 命名查询性能分析结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryPerformanceResult {

    /**
     * 分析是否成功
     */
    private Boolean success;

    /**
     * 分析消息
     */
    private String message;

    /**
     * 查询ID
     */
    private Long queryId;

    /**
     * 分析开始时间
     */
    private LocalDateTime startTime;

    /**
     * 分析结束时间
     */
    private LocalDateTime endTime;

    /**
     * 分析总时长（毫秒）
     */
    private Long totalAnalysisTimeMs;

    /**
     * 执行统计
     */
    private ExecutionStatistics executionStats;

    /**
     * 性能指标
     */
    private PerformanceMetrics performanceMetrics;

    /**
     * 执行计划分析
     */
    private ExecutionPlanAnalysis executionPlanAnalysis;

    /**
     * 索引使用分析
     */
    private IndexUsageAnalysis indexUsageAnalysis;

    /**
     * 资源消耗分析
     */
    private ResourceUsageAnalysis resourceUsageAnalysis;

    /**
     * 优化建议
     */
    private List<OptimizationSuggestion> optimizationSuggestions;

    /**
     * 性能等级
     */
    private String performanceGrade; // EXCELLENT, GOOD, FAIR, POOR, CRITICAL

    /**
     * 性能评分（0-100）
     */
    private Integer performanceScore;

    /**
     * 分析环境
     */
    private String analysisEnvironment;

    /**
     * 执行统计内部类
     */
    @Data
    public static class ExecutionStatistics {
        private Integer totalExecutions;
        private Integer successfulExecutions;
        private Integer failedExecutions;
        private Double averageExecutionTimeMs;
        private Double minExecutionTimeMs;
        private Double maxExecutionTimeMs;
        private Double standardDeviation;
        private Double throughputQps;
        private Long totalRowsProcessed;
        private Long averageRowsPerExecution;
    }

    /**
     * 性能指标内部类
     */
    @Data
    public static class PerformanceMetrics {
        private Double cpuUsagePercent;
        private Long memoryUsedBytes;
        private Long diskIoBytes;
        private Long networkIoBytes;
        private Integer connectionPoolUsage;
        private Double cacheHitRatio;
        private Long tempSpaceUsedBytes;
        private Integer lockWaitCount;
        private Double lockWaitTimeMs;
    }

    /**
     * 执行计划分析内部类
     */
    @Data
    public static class ExecutionPlanAnalysis {
        private String executionPlan;
        private List<String> expensiveOperations;
        private List<String> missingIndexes;
        private List<String> inefficientJoins;
        private Double estimatedCost;
        private Long estimatedRows;
        private String planComplexity; // SIMPLE, MODERATE, COMPLEX, VERY_COMPLEX
    }

    /**
     * 索引使用分析内部类
     */
    @Data
    public static class IndexUsageAnalysis {
        private List<String> usedIndexes;
        private List<String> unusedIndexes;
        private List<String> recommendedIndexes;
        private Double indexHitRatio;
        private Long indexScanCount;
        private Long tableScanCount;
        private String indexEfficiency; // EXCELLENT, GOOD, FAIR, POOR
    }

    /**
     * 资源消耗分析内部类
     */
    @Data
    public static class ResourceUsageAnalysis {
        private Map<String, Object> cpuMetrics;
        private Map<String, Object> memoryMetrics;
        private Map<String, Object> ioMetrics;
        private Map<String, Object> networkMetrics;
        private String resourceEfficiency; // EXCELLENT, GOOD, FAIR, POOR
        private List<String> resourceBottlenecks;
    }

    /**
     * 优化建议内部类
     */
    @Data
    public static class OptimizationSuggestion {
        private String suggestionType; // INDEX, QUERY_REWRITE, SCHEMA_CHANGE, CONFIG_TUNING
        private String priority; // HIGH, MEDIUM, LOW
        private String description;
        private String implementation;
        private String expectedImprovement;
        private Integer estimatedEffort; // 1-10 scale
        private List<String> prerequisites;
    }

    public NamedQueryPerformanceResult() {
        this.startTime = LocalDateTime.now(ZoneOffset.UTC);
    }

    public NamedQueryPerformanceResult(Boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
    }

    public static NamedQueryPerformanceResult success(String message) {
        return new NamedQueryPerformanceResult(true, message);
    }

    public static NamedQueryPerformanceResult failure(String message) {
        return new NamedQueryPerformanceResult(false, message);
    }

    /**
     * 完成分析
     */
    public void complete() {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
        this.totalAnalysisTimeMs = this.endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - this.startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}