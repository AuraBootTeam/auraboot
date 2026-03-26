package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 查询异常检测结果DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAnomalyDetectionResult {

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 检测开始时间
     */
    private LocalDateTime startTime;

    /**
     * 检测结束时间
     */
    private LocalDateTime endTime;

    /**
     * 是否检测到异常
     */
    private Boolean anomaliesDetected;

    /**
     * 异常总数
     */
    private Integer totalAnomalies;

    /**
     * 高风险异常数量
     */
    private Integer highRiskAnomalies;

    /**
     * 中风险异常数量
     */
    private Integer mediumRiskAnomalies;

    /**
     * 低风险异常数量
     */
    private Integer lowRiskAnomalies;

    /**
     * 检测到的异常列表
     */
    private List<QueryAnomaly> anomalies;

    /**
     * 异常统计信息
     */
    private AnomalyStatistics statistics;

    /**
     * 异常模式分析
     */
    private List<AnomalyPattern> patterns;

    /**
     * 风险评估结果
     */
    private RiskAssessment riskAssessment;

    /**
     * 建议的处理措施
     */
    private List<RecommendedAction> recommendedActions;

    /**
     * 检测执行信息
     */
    private DetectionExecutionInfo executionInfo;

    /**
     * 查询异常
     */
    @Data
    public static class QueryAnomaly {
        private String anomalyId;
        private String anomalyType;
        private String severity;
        private Integer riskScore;
        private String description;
        private Long userId;
        private String modelCode;
        private String queryId;
        private LocalDateTime detectedAt;
        private String queryConditions;
        private Map<String, Object> anomalyDetails;
        private List<String> evidences;
        private String recommendedAction;
        private Boolean handled;
    }

    /**
     * 异常统计信息
     */
    @Data
    public static class AnomalyStatistics {
        private Map<String, Integer> anomalyTypeCount;
        private Map<String, Integer> severityCount;
        private Map<Long, Integer> userAnomalyCount;
        private Map<String, Integer> modelAnomalyCount;
        private Map<String, Integer> hourlyAnomalyCount;
        private Double averageRiskScore;
        private Integer maxRiskScore;
        private Integer minRiskScore;
    }

    /**
     * 异常模式
     */
    @Data
    public static class AnomalyPattern {
        private String patternId;
        private String patternType;
        private String description;
        private Integer occurrenceCount;
        private List<String> affectedQueries;
        private List<Long> affectedUsers;
        private Double confidenceScore;
        private String riskLevel;
        private String recommendation;
    }

    /**
     * 风险评估
     */
    @Data
    public static class RiskAssessment {
        private String overallRiskLevel;
        private Integer overallRiskScore;
        private List<RiskFactor> riskFactors;
        private List<String> criticalFindings;
        private String riskTrend;
        private String nextAssessmentRecommendation;
    }

    /**
     * 风险因子
     */
    @Data
    public static class RiskFactor {
        private String factorType;
        private String description;
        private Integer weight;
        private Integer score;
        private String impact;
    }

    /**
     * 建议措施
     */
    @Data
    public static class RecommendedAction {
        private String actionType;
        private String description;
        private String priority;
        private String targetComponent;
        private String expectedOutcome;
        private Integer estimatedEffort;
        private List<String> prerequisites;
    }

    /**
     * 检测执行信息
     */
    @Data
    public static class DetectionExecutionInfo {
        private LocalDateTime executionStartTime;
        private LocalDateTime executionEndTime;
        private Long executionDurationMs;
        private Integer analyzedQueryCount;
        private Integer analyzedUserCount;
        private Integer analyzedModelCount;
        private String detectionVersion;
        private List<String> appliedRules;
        private Map<String, Object> detectionParameters;
    }
}