package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 查询优化建议DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryOptimizationSuggestion {

    /**
     * 建议ID
     */
    private String suggestionId;

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
     * 建议类型
     */
    private String suggestionType;

    /**
     * 建议分类
     */
    private String category;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 严重程度
     */
    private String severity;

    /**
     * 建议标题
     */
    private String title;

    /**
     * 建议描述
     */
    private String description;

    /**
     * 问题分析
     */
    private String problemAnalysis;

    /**
     * 目标组件
     */
    private String targetComponent;

    /**
     * 预期改进效果
     */
    private Double expectedImprovement;

    /**
     * 预期改进类型
     */
    private String improvementType;

    /**
     * 实施复杂度
     */
    private String implementationComplexity;

    /**
     * 预估实施时间
     */
    private String estimatedImplementationTime;

    /**
     * 实施成本
     */
    private String implementationCost;

    /**
     * 风险评估
     */
    private String riskAssessment;

    /**
     * 实施步骤
     */
    private List<ImplementationStep> implementationSteps;

    /**
     * 相关指标
     */
    private List<PerformanceMetric> relatedMetrics;

    /**
     * 优化前后对比
     */
    private BeforeAfterComparison comparison;

    /**
     * 适用条件
     */
    private List<String> applicableConditions;

    /**
     * 前置条件
     */
    private List<String> prerequisites;

    /**
     * 依赖项
     */
    private List<String> dependencies;

    /**
     * 相关建议
     */
    private List<String> relatedSuggestions;

    /**
     * 参考资料
     */
    private List<String> references;

    /**
     * 示例代码
     */
    private String exampleCode;

    /**
     * 配置示例
     */
    private Map<String, Object> configurationExample;

    /**
     * 验证方法
     */
    private List<String> validationMethods;

    /**
     * 监控指标
     */
    private List<String> monitoringMetrics;

    /**
     * 回滚计划
     */
    private String rollbackPlan;

    /**
     * 建议状态
     */
    private String status;

    /**
     * 应用状态
     */
    private String applicationStatus;

    /**
     * 应用时间
     */
    private LocalDateTime appliedAt;

    /**
     * 应用人
     */
    private Long appliedBy;

    /**
     * 应用结果
     */
    private String applicationResult;

    /**
     * 实际改进效果
     */
    private Double actualImprovement;

    /**
     * 生成时间
     */
    private LocalDateTime generatedAt;

    /**
     * 过期时间
     */
    private LocalDateTime expiresAt;

    /**
     * 置信度
     */
    private Double confidence;

    /**
     * 数据来源
     */
    private String dataSource;

    /**
     * 分析版本
     */
    private String analysisVersion;

    /**
     * 自定义属性
     */
    private Map<String, Object> customAttributes;

    /**
     * 实施步骤
     */
    @Data
    public static class ImplementationStep {
        private Integer stepOrder;
        private String stepTitle;
        private String stepDescription;
        private String stepType;
        private List<String> actions;
        private String expectedOutcome;
        private String estimatedDuration;
        private List<String> requiredSkills;
        private List<String> requiredTools;
        private String riskLevel;
        private List<String> validationCriteria;
    }

    /**
     * 性能指标
     */
    @Data
    public static class PerformanceMetric {
        private String metricName;
        private String metricType;
        private Object currentValue;
        private Object targetValue;
        private String unit;
        private String improvementDirection;
        private Double improvementPercentage;
        private String measurementMethod;
    }

    /**
     * 优化前后对比
     */
    @Data
    public static class BeforeAfterComparison {
        private PerformanceSnapshot before;
        private PerformanceSnapshot after;
        private List<ImprovementMetric> improvements;
        private String overallAssessment;
    }

    /**
     * 性能快照
     */
    @Data
    public static class PerformanceSnapshot {
        private Double executionTime;
        private Long memoryUsage;
        private Long cpuUsage;
        private Long ioOperations;
        private Integer concurrency;
        private Double throughput;
        private Double errorRate;
        private Map<String, Object> additionalMetrics;
    }

    /**
     * 改进指标
     */
    @Data
    public static class ImprovementMetric {
        private String metricName;
        private Object beforeValue;
        private Object afterValue;
        private Double improvementPercentage;
        private String improvementType;
        private String significance;
    }
}