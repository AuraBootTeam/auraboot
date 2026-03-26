package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 查询审计报告DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAuditReport {

    /**
     * 报告ID
     */
    private String reportId;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 报告标题
     */
    private String title;

    /**
     * 报告类型
     */
    private String reportType;

    /**
     * 报告周期开始时间
     */
    private LocalDateTime periodStartTime;

    /**
     * 报告周期结束时间
     */
    private LocalDateTime periodEndTime;

    /**
     * 报告生成时间
     */
    private LocalDateTime generatedAt;

    /**
     * 报告生成人
     */
    private Long generatedBy;

    /**
     * 执行摘要
     */
    private ExecutiveSummary executiveSummary;

    /**
     * 查询活动概览
     */
    private QueryActivityOverview queryActivityOverview;

    /**
     * 性能分析
     */
    private PerformanceAnalysis performanceAnalysis;

    /**
     * 安全分析
     */
    private SecurityAnalysis securityAnalysis;

    /**
     * 用户活动分析
     */
    private UserActivityAnalysis userActivityAnalysis;

    /**
     * 模型使用分析
     */
    private ModelUsageAnalysis modelUsageAnalysis;

    /**
     * 异常检测结果
     */
    private AnomalyDetectionSummary anomalyDetectionSummary;

    /**
     * 趋势分析
     */
    private TrendAnalysis trendAnalysis;

    /**
     * 建议和行动项
     */
    private List<RecommendationItem> recommendations;

    /**
     * 附录数据
     */
    private Map<String, Object> appendixData;

    /**
     * 报告配置
     */
    private ReportConfiguration configuration;

    /**
     * 执行摘要
     */
    @Data
    public static class ExecutiveSummary {
        private Long totalQueries;
        private Double successRate;
        private Double averageResponseTime;
        private Integer criticalIssues;
        private Integer warnings;
        private String overallHealthStatus;
        private List<String> keyFindings;
        private List<String> immediateActions;
    }

    /**
     * 查询活动概览
     */
    @Data
    public static class QueryActivityOverview {
        private Long totalQueries;
        private Long successfulQueries;
        private Long failedQueries;
        private Map<String, Long> queryTypeDistribution;
        private Map<String, Long> dailyQueryCounts;
        private Map<Integer, Long> hourlyQueryCounts;
        private List<String> mostActiveUsers;
        private List<String> mostQueriedModels;
    }

    /**
     * 性能分析
     */
    @Data
    public static class PerformanceAnalysis {
        private Double averageExecutionTime;
        private Double medianExecutionTime;
        private Double p95ExecutionTime;
        private Double p99ExecutionTime;
        private Long slowQueryCount;
        private Double slowQueryRate;
        private List<SlowQuerySummary> topSlowQueries;
        private Map<String, Double> modelPerformanceRanking;
        private PerformanceTrendSummary performanceTrend;
    }

    /**
     * 安全分析
     */
    @Data
    public static class SecurityAnalysis {
        private Long securityEventCount;
        private Map<String, Long> securityEventTypes;
        private Long suspiciousActivityCount;
        private List<SecurityIncident> criticalSecurityIncidents;
        private Map<String, Long> failedPermissionChecks;
        private Long dataMaskingApplications;
        private SecurityRiskAssessment riskAssessment;
    }

    /**
     * 用户活动分析
     */
    @Data
    public static class UserActivityAnalysis {
        private Long activeUserCount;
        private Map<Long, Long> userQueryCounts;
        private Map<Long, Double> userAverageResponseTimes;
        private List<UserActivitySummary> topActiveUsers;
        private List<UserActivitySummary> unusualUserActivity;
        private Map<String, Long> userAccessPatterns;
    }

    /**
     * 模型使用分析
     */
    @Data
    public static class ModelUsageAnalysis {
        private Long activeModelCount;
        private Map<String, Long> modelQueryCounts;
        private Map<String, Double> modelAverageResponseTimes;
        private List<ModelUsageSummary> topUsedModels;
        private List<ModelUsageSummary> underutilizedModels;
        private Map<String, Double> modelErrorRates;
    }

    /**
     * 异常检测摘要
     */
    @Data
    public static class AnomalyDetectionSummary {
        private Long totalAnomalies;
        private Map<String, Long> anomalyTypes;
        private Map<String, Long> anomalySeverities;
        private List<AnomalySummary> criticalAnomalies;
        private Double anomalyRate;
        private String anomalyTrend;
    }

    /**
     * 趋势分析
     */
    @Data
    public static class TrendAnalysis {
        private QueryVolumeTrend queryVolumeTrend;
        private PerformanceTrendSummary performanceTrend;
        private ErrorRateTrend errorRateTrend;
        private UserActivityTrend userActivityTrend;
        private SecurityTrend securityTrend;
        private List<String> trendInsights;
    }

    /**
     * 慢查询摘要
     */
    @Data
    public static class SlowQuerySummary {
        private String queryId;
        private String modelCode;
        private Integer executionTime;
        private Integer occurrenceCount;
        private String optimizationSuggestion;
    }

    /**
     * 安全事件
     */
    @Data
    public static class SecurityIncident {
        private String incidentId;
        private String incidentType;
        private String severity;
        private LocalDateTime occurredAt;
        private String description;
        private String status;
    }

    /**
     * 安全风险评估
     */
    @Data
    public static class SecurityRiskAssessment {
        private String overallRiskLevel;
        private Integer riskScore;
        private List<String> riskFactors;
        private List<String> mitigationRecommendations;
    }

    /**
     * 用户活动摘要
     */
    @Data
    public static class UserActivitySummary {
        private Long userId;
        private String userName;
        private Long queryCount;
        private Double averageResponseTime;
        private Long errorCount;
        private String activityPattern;
    }

    /**
     * 模型使用摘要
     */
    @Data
    public static class ModelUsageSummary {
        private String modelCode;
        private String modelName;
        private Long queryCount;
        private Double averageResponseTime;
        private Double errorRate;
        private String usagePattern;
    }

    /**
     * 异常摘要
     */
    @Data
    public static class AnomalySummary {
        private String anomalyId;
        private String anomalyType;
        private String severity;
        private LocalDateTime detectedAt;
        private String description;
        private String status;
    }

    /**
     * 查询量趋势
     */
    @Data
    public static class QueryVolumeTrend {
        private String trendDirection;
        private Double changePercentage;
        private List<TrendDataPoint> dataPoints;
        private String analysis;
    }

    /**
     * 性能趋势摘要
     */
    @Data
    public static class PerformanceTrendSummary {
        private String trendDirection;
        private Double changePercentage;
        private List<TrendDataPoint> dataPoints;
        private String analysis;
    }

    /**
     * 错误率趋势
     */
    @Data
    public static class ErrorRateTrend {
        private String trendDirection;
        private Double changePercentage;
        private List<TrendDataPoint> dataPoints;
        private String analysis;
    }

    /**
     * 用户活动趋势
     */
    @Data
    public static class UserActivityTrend {
        private String trendDirection;
        private Double changePercentage;
        private List<TrendDataPoint> dataPoints;
        private String analysis;
    }

    /**
     * 安全趋势
     */
    @Data
    public static class SecurityTrend {
        private String trendDirection;
        private Double changePercentage;
        private List<TrendDataPoint> dataPoints;
        private String analysis;
    }

    /**
     * 趋势数据点
     */
    @Data
    public static class TrendDataPoint {
        private LocalDateTime timestamp;
        private Double value;
        private String label;
    }

    /**
     * 建议项
     */
    @Data
    public static class RecommendationItem {
        private String category;
        private String title;
        private String description;
        private String priority;
        private String impact;
        private String effort;
        private List<String> actionSteps;
        private String expectedOutcome;
    }

    /**
     * 报告配置
     */
    @Data
    public static class ReportConfiguration {
        private String reportFormat;
        private List<String> includedSections;
        private Map<String, Object> filterCriteria;
        private String detailLevel;
        private Boolean includeCharts;
        private Boolean includeRawData;
    }
}