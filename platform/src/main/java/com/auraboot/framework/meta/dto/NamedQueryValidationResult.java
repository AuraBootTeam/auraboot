package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 命名查询验证结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryValidationResult {

    /**
     * 验证是否通过
     */
    private Boolean valid;

    /**
     * 验证类型
     */
    private String validationType;

    /**
     * 错误列表
     */
    private List<NamedQueryValidationError> errors;

    /**
     * 警告列表
     */
    private List<NamedQueryValidationWarning> warnings;

    /**
     * 验证详情
     */
    private Map<String, Object> validationDetails;

    /**
     * SQL语法检查结果
     */
    private NamedQuerySqlValidation sqlValidation;

    /**
     * 字段验证结果
     */
    private List<NamedQueryFieldValidation> fieldValidations;

    /**
     * 权限验证结果
     */
    private NamedQueryPermissionValidation permissionValidation;

    /**
     * 依赖验证结果
     */
    private NamedQueryDependencyValidation dependencyValidation;

    /**
     * 验证开始时间
     */
    private LocalDateTime startTime;

    /**
     * 验证结束时间
     */
    private LocalDateTime endTime;

    /**
     * 验证耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 验证者
     */
    private String validator;

    /**
     * 验证环境
     */
    private String validationEnv;

    /**
     * 验证摘要
     */
    private String summary;

    /**
     * 建议修复方案
     */
    private List<String> suggestions;

    /**
     * 构造函数
     */
    public NamedQueryValidationResult() {
        this.startTime = LocalDateTime.now(ZoneOffset.UTC);
        this.valid = true;
        this.errors = new java.util.ArrayList<>();
        this.warnings = new java.util.ArrayList<>();
        this.suggestions = new java.util.ArrayList<>();
    }

    /**
     * 构造函数
     * @param validationType 验证类型
     */
    public NamedQueryValidationResult(String validationType) {
        this();
        this.validationType = validationType;
    }

    /**
     * 添加错误
     * @param code 错误代码
     * @param message 错误信息
     * @param field 相关字段
     */
    public void addError(String code, String message, String field) {
        NamedQueryValidationError error = new NamedQueryValidationError();
        error.setCode(code);
        error.setMessage(message);
        error.setField(field);
        error.setSeverity("error");
        this.errors.add(error);
        this.valid = false;
    }

    /**
     * 添加错误
     * @param code 错误代码
     * @param message 错误信息
     */
    public void addError(String code, String message) {
        addError(code, message, null);
    }

    /**
     * 添加警告
     * @param code 警告代码
     * @param message 警告信息
     * @param field 相关字段
     */
    public void addWarning(String code, String message, String field) {
        NamedQueryValidationWarning warning = new NamedQueryValidationWarning();
        warning.setCode(code);
        warning.setMessage(message);
        warning.setField(field);
        warning.setSeverity("warning");
        this.warnings.add(warning);
    }

    /**
     * 添加警告
     * @param code 警告代码
     * @param message 警告信息
     */
    public void addWarning(String code, String message) {
        addWarning(code, message, null);
    }

    /**
     * 添加建议
     * @param suggestion 建议内容
     */
    public void addSuggestion(String suggestion) {
        this.suggestions.add(suggestion);
    }

    /**
     * 完成验证
     */
    public void complete() {
        this.endTime = LocalDateTime.now(ZoneOffset.UTC);
        this.durationMs = endTime.toInstant(ZoneOffset.UTC).toEpochMilli() - startTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        
        // 生成验证摘要
        StringBuilder sb = new StringBuilder();
        sb.append(validationType).append("验证");
        if (valid) {
            sb.append("通过");
        } else {
            sb.append("失败");
        }
        sb.append("，发现").append(errors.size()).append("个错误");
        if (!warnings.isEmpty()) {
            sb.append("，").append(warnings.size()).append("个警告");
        }
        sb.append("，耗时").append(durationMs).append("毫秒");
        this.summary = sb.toString();
    }

    /**
     * 是否有错误
     * @return 是否有错误
     */
    public Boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    /**
     * 是否有警告
     * @return 是否有警告
     */
    public Boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    /**
     * 获取错误数量
     * @return 错误数量
     */
    public Integer getErrorCount() {
        return errors != null ? errors.size() : 0;
    }

    /**
     * 获取警告数量
     * @return 警告数量
     */
    public Integer getWarningCount() {
        return warnings != null ? warnings.size() : 0;
    }

    /**
     * 获取第一个错误信息
     * @return 第一个错误信息
     */
    public String getFirstErrorMessage() {
        if (hasErrors()) {
            return errors.get(0).getMessage();
        }
        return null;
    }

    /**
     * 获取所有错误信息
     * @return 所有错误信息
     */
    public List<String> getAllErrorMessages() {
        if (errors == null) {
            return new java.util.ArrayList<>();
        }
        return errors.stream()
                .map(NamedQueryValidationError::getMessage)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 获取所有警告信息
     * @return 所有警告信息
     */
    public List<String> getAllWarningMessages() {
        if (warnings == null) {
            return new java.util.ArrayList<>();
        }
        return warnings.stream()
                .map(NamedQueryValidationWarning::getMessage)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 验证错误内部类
     */
    @Data
    public static class NamedQueryValidationError {
        private String code;
        private String message;
        private String field;
        private String severity;
        private Integer line;
        private Integer column;
        private String context;
    }

    /**
     * 验证警告内部类
     */
    @Data
    public static class NamedQueryValidationWarning {
        private String code;
        private String message;
        private String field;
        private String severity;
        private Integer line;
        private Integer column;
        private String context;
    }

    /**
     * SQL验证结果内部类
     */
    @Data
    public static class NamedQuerySqlValidation {
        private Boolean syntaxValid;
        private String syntaxError;
        private Boolean securityValid;
        private List<String> securityIssues;
        private Boolean performanceValid;
        private List<String> performanceWarnings;
    }

    /**
     * 字段验证结果内部类
     */
    @Data
    public static class NamedQueryFieldValidation {
        private String fieldCode;
        private Boolean valid;
        private List<String> errors;
        private List<String> warnings;
    }

    /**
     * 权限验证结果内部类
     */
    @Data
    public static class NamedQueryPermissionValidation {
        private Boolean hasAccess;
        private List<String> missingPermissions;
        private List<String> restrictedFields;
    }

    /**
     * 依赖验证结果内部类
     */
    @Data
    public static class NamedQueryDependencyValidation {
        private Boolean dependenciesValid;
        private List<String> missingDependencies;
        private List<String> circularDependencies;
    }
}