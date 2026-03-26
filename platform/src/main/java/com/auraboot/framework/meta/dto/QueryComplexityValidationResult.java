package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 查询复杂度验证结果DTO
 */
@Data
public class QueryComplexityValidationResult {

    /**
     * 是否通过验证
     */
    private Boolean valid;

    /**
     * 复杂度分数
     */
    private Integer complexityScore;

    /**
     * 最大允许分数
     */
    private Integer maxAllowedScore;

    /**
     * 验证失败原因
     */
    private String reason;

    /**
     * 复杂度详情
     */
    private ComplexityDetails details;

    /**
     * 复杂度详情
     */
    @Data
    public static class ComplexityDetails {
        private Integer conditionComplexity;
        private Integer sortComplexity;
        private Integer relationComplexity;
        private Integer aggregateComplexity;
        private Integer totalComplexity;
    }
}