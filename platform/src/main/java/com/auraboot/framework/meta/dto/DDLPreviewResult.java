package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * DDL预览结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class DDLPreviewResult {
    
    /**
     * 模型编码
     */
    private String modelCode;
    
    /**
     * 预览的DDL语句列表
     */
    private List<String> ddlStatements;
    
    /**
     * 操作类型
     */
    private String operationType;
    
    /**
     * 影响的表
     */
    private List<String> affectedTables;
    
    /**
     * 风险评估
     */
    private RiskAssessment riskAssessment;
    
    @Data
    @Builder
    public static class RiskAssessment {
        private String level; // LOW, MEDIUM, HIGH
        private String description;
        private List<String> warnings;
    }
}