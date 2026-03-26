package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * 查询安全验证结果DTO
 */
@Data
public class QuerySecurityValidationResult {

    /**
     * 是否通过验证
     */
    private Boolean valid;

    /**
     * 验证错误信息
     */
    private List<String> errors;

    /**
     * 验证警告信息
     */
    private List<String> warnings;

    /**
     * 安全风险等级
     */
    private SecurityRiskLevel riskLevel;

    /**
     * 检测到的安全问题
     */
    private List<SecurityIssue> securityIssues;

    /**
     * 验证耗时（毫秒）
     */
    private Long validationTimeMs;

    /**
     * 安全风险等级枚举
     */
    public enum SecurityRiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 安全问题
     */
    @Data
    public static class SecurityIssue {
        private String type;
        private String description;
        private String field;
        private String value;
        private SecurityRiskLevel severity;
    }
}