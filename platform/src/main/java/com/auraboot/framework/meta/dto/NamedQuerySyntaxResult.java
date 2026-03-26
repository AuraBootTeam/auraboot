package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 命名查询语法验证结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQuerySyntaxResult {

    /**
     * 验证是否成功
     */
    private Boolean valid;

    /**
     * 验证消息
     */
    private String message;

    /**
     * 验证时间
     */
    private LocalDateTime validationTime;

    /**
     * 语法错误列表
     */
    private List<SyntaxError> syntaxErrors;

    /**
     * 警告信息列表
     */
    private List<SyntaxWarning> warnings;

    /**
     * 性能风险列表
     */
    private List<PerformanceRisk> performanceRisks;

    /**
     * 安全风险列表
     */
    private List<SecurityRisk> securityRisks;

    /**
     * 建议信息列表
     */
    private List<String> suggestions;

    /**
     * 验证详情
     */
    private String validationDetails;

    /**
     * 语法错误内部类
     */
    @Data
    public static class SyntaxError {
        private String errorCode;
        private String errorMessage;
        private Integer lineNumber;
        private Integer columnNumber;
        private String errorType;
        private String severity; // ERROR, WARNING, INFO
    }

    /**
     * 语法警告内部类
     */
    @Data
    public static class SyntaxWarning {
        private String warningCode;
        private String warningMessage;
        private Integer lineNumber;
        private String warningType;
        private String suggestion;
    }

    /**
     * 性能风险内部类
     */
    @Data
    public static class PerformanceRisk {
        private String riskCode;
        private String riskDescription;
        private String riskLevel; // HIGH, MEDIUM, LOW
        private String recommendation;
        private String affectedPart;
    }

    /**
     * 安全风险内部类
     */
    @Data
    public static class SecurityRisk {
        private String riskCode;
        private String riskDescription;
        private String riskLevel; // CRITICAL, HIGH, MEDIUM, LOW
        private String mitigation;
        private String affectedPart;
    }

    public NamedQuerySyntaxResult() {
        this.validationTime = LocalDateTime.now(ZoneOffset.UTC);
    }

    public NamedQuerySyntaxResult(Boolean valid, String message) {
        this();
        this.valid = valid;
        this.message = message;
    }

    public static NamedQuerySyntaxResult valid(String message) {
        return new NamedQuerySyntaxResult(true, message);
    }

    public static NamedQuerySyntaxResult invalid(String message) {
        return new NamedQuerySyntaxResult(false, message);
    }
}