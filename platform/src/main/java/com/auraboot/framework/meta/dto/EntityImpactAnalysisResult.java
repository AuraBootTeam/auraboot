package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 实体影响分析结果DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityImpactAnalysisResult {
    
    /**
     * 分析的实体ID
     */
    private Long entityId;
    
    /**
     * 分析的实体编码
     */
    private String entityCode;
    
    /**
     * 分析的实体名称
     */
    private String entityName;
    
    /**
     * 变更类型
     */
    private String changeType;
    
    /**
     * 分析时间
     */
    private LocalDateTime analyzedAt;
    
    /**
     * 受影响的实体编码列表
     */
    private List<String> impactedEntities;
    
    /**
     * 直接影响的实体列表
     */
    private List<ImpactedEntity> directImpacts;
    
    /**
     * 间接影响的实体列表
     */
    private List<ImpactedEntity> indirectImpacts;
    
    /**
     * 影响的表单列表
     */
    private List<ImpactedForm> impactedForms;
    
    /**
     * 影响的API接口列表
     */
    private List<ImpactedApi> impactedApis;
    
    /**
     * 风险评估
     */
    private RiskAssessment riskAssessment;
    
    /**
     * 建议的迁移步骤
     */
    private List<String> migrationSteps;
    
    /**
     * 影响统计
     */
    private Map<String, Integer> impactStatistics;
    
    /**
     * 受影响的实体信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImpactedEntity {
        private Long entityId;
        private String entityCode;
        private String entityName;
        private String impactType;
        private String impactDescription;
        private String severity;
    }
    
    /**
     * 受影响的表单信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImpactedForm {
        private Long formId;
        private String formCode;
        private String formName;
        private String impactType;
        private List<String> affectedFields;
    }
    
    /**
     * 受影响的API信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImpactedApi {
        private String apiPath;
        private String apiMethod;
        private String apiName;
        private String impactType;
        private String impactDescription;
    }
    
    /**
     * 风险评估信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskAssessment {
        private String riskLevel;
        private String riskDescription;
        private List<String> riskFactors;
        private List<String> mitigationStrategies;
    }
}