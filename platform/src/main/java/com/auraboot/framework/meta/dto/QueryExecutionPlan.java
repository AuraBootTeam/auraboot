package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 查询执行计划DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryExecutionPlan {

    /**
     * 执行计划ID
     */
    private String planId;

    /**
     * 查询ID
     */
    private String queryId;

    /**
     * 模型编码
     */
    private String modelCode;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 生成时间
     */
    private LocalDateTime generatedAt;

    /**
     * 预估执行时间(毫秒)
     */
    private Long estimatedExecutionTime;

    /**
     * 预估结果数量
     */
    private Long estimatedResultCount;

    /**
     * 查询复杂度分数
     */
    private Integer complexityScore;

    /**
     * 执行步骤
     */
    private List<ExecutionStep> executionSteps;

    /**
     * 索引使用情况
     */
    private List<IndexUsage> indexUsages;

    /**
     * 资源消耗预估
     */
    private ResourceConsumption resourceConsumption;

    /**
     * 性能警告
     */
    private List<PerformanceWarning> performanceWarnings;

    /**
     * 优化建议
     */
    private List<OptimizationSuggestion> optimizationSuggestions;

    /**
     * 查询统计信息
     */
    private QueryStatistics queryStatistics;

    /**
     * 执行环境信息
     */
    private ExecutionEnvironment executionEnvironment;

    /**
     * 执行步骤
     */
    @Data
    public static class ExecutionStep {
        private Integer stepOrder;
        private String stepType;
        private String stepDescription;
        private String operation;
        private String targetTable;
        private List<String> involvedFields;
        private String condition;
        private Long estimatedRows;
        private Long estimatedCost;
        private String accessMethod;
        private List<String> usedIndexes;
        private Map<String, Object> stepDetails;
    }

    /**
     * 索引使用情况
     */
    @Data
    public static class IndexUsage {
        private String indexName;
        private String tableName;
        private List<String> indexColumns;
        private String usageType;
        private Double selectivity;
        private Long estimatedRows;
        private Boolean isOptimal;
        private String recommendation;
    }

    /**
     * 资源消耗预估
     */
    @Data
    public static class ResourceConsumption {
        private Long estimatedMemoryUsage;
        private Long estimatedCpuTime;
        private Long estimatedIoOperations;
        private Long estimatedNetworkTraffic;
        private Integer estimatedConcurrency;
        private String resourceLevel;
    }

    /**
     * 性能警告
     */
    @Data
    public static class PerformanceWarning {
        private String warningType;
        private String severity;
        private String description;
        private String affectedComponent;
        private String potentialImpact;
        private List<String> recommendations;
    }

    /**
     * 优化建议
     */
    @Data
    public static class OptimizationSuggestion {
        private String suggestionType;
        private String category;
        private String description;
        private String targetComponent;
        private Double expectedImprovement;
        private String implementationComplexity;
        private Integer priority;
        private List<String> actionSteps;
    }

    /**
     * 查询统计信息
     */
    @Data
    public static class QueryStatistics {
        private Long historicalExecutionCount;
        private Double averageExecutionTime;
        private Double medianExecutionTime;
        private Long maxExecutionTime;
        private Long minExecutionTime;
        private Double successRate;
        private LocalDateTime lastExecutionTime;
        private String executionTrend;
    }

    /**
     * 执行环境信息
     */
    @Data
    public static class ExecutionEnvironment {
        private String databaseVersion;
        private String engineVersion;
        private Map<String, String> systemParameters;
        private Long availableMemory;
        private Integer availableCpuCores;
        private String storageType;
        private Integer currentConnections;
        private Double systemLoad;
    }
}