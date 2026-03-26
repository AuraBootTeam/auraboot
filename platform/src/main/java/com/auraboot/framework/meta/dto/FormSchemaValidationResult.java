package com.auraboot.framework.meta.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * FormSchema验证结果DTO
 * 封装FormSchema验证的结果信息
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
public class FormSchemaValidationResult {
    
    /**
     * 验证是否通过
     */
    private boolean valid;
    
    /**
     * 验证结果消息
     */
    private String message;
    
    /**
     * 验证开始时间
     */
    private LocalDateTime validationStartTime;
    
    /**
     * 验证结束时间
     */
    private LocalDateTime validationEndTime;
    
    /**
     * 验证错误列表
     */
    private List<ValidationError> errors;
    
    /**
     * 验证警告列表
     */
    private List<ValidationWarning> warnings;
    
    /**
     * 验证规则检查结果
     */
    private Map<String, ValidationRuleResult> ruleResults;
    
    /**
     * 字段验证结果
     */
    private Map<String, FieldValidationResult> fieldResults;
    
    /**
     * 验证统计信息
     */
    private ValidationStatistics statistics;
    
    /**
     * 验证级别
     */
    private ValidationLevel level;
    
    /**
     * 验证上下文
     */
    private Map<String, Object> context;
    
    /**
     * 默认构造函数
     */
    public FormSchemaValidationResult() {
        this.validationStartTime = LocalDateTime.now(ZoneOffset.UTC);
        this.level = ValidationLevel.STRICT;
    }
    
    /**
     * 创建成功验证结果
     * 
     * @param message 成功消息
     * @return 验证结果
     */
    public static FormSchemaValidationResult success(String message) {
        FormSchemaValidationResult result = new FormSchemaValidationResult();
        result.valid = true;
        result.message = message != null ? message : "验证通过";
        result.validationEndTime = LocalDateTime.now(ZoneOffset.UTC);
        return result;
    }
    
    /**
     * 创建失败验证结果
     * 
     * @param message 失败消息
     * @return 验证结果
     */
    public static FormSchemaValidationResult failure(String message) {
        FormSchemaValidationResult result = new FormSchemaValidationResult();
        result.valid = false;
        result.message = message != null ? message : "验证失败";
        result.validationEndTime = LocalDateTime.now(ZoneOffset.UTC);
        return result;
    }
    
    /**
     * 标记验证完成
     */
    public void markCompleted() {
        this.validationEndTime = LocalDateTime.now(ZoneOffset.UTC);
    }
    
    /**
     * 计算验证耗时（毫秒）
     * 
     * @return 验证耗时
     */
    public long getValidationTimeMillis() {
        if (validationStartTime != null && validationEndTime != null) {
            return java.time.Duration.between(validationStartTime, validationEndTime).toMillis();
        }
        return 0;
    }
    
    /**
     * 添加验证错误
     * 
     * @param error 验证错误
     */
    public void addError(ValidationError error) {
        if (this.errors == null) {
            this.errors = new java.util.ArrayList<>();
        }
        this.errors.add(error);
        this.valid = false;
    }
    
    /**
     * 添加验证警告
     * 
     * @param warning 验证警告
     */
    public void addWarning(ValidationWarning warning) {
        if (this.warnings == null) {
            this.warnings = new java.util.ArrayList<>();
        }
        this.warnings.add(warning);
    }
    
    /**
     * 检查是否有错误
     * 
     * @return 是否有错误
     */
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
    
    /**
     * 检查是否有警告
     * 
     * @return 是否有警告
     */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
    
    /**
     * 获取错误数量
     * 
     * @return 错误数量
     */
    public int getErrorCount() {
        return errors != null ? errors.size() : 0;
    }
    
    /**
     * 获取警告数量
     * 
     * @return 警告数量
     */
    public int getWarningCount() {
        return warnings != null ? warnings.size() : 0;
    }
    
    // Getters and Setters
    public boolean isValid() {
        return valid;
    }
    
    public void setValid(boolean valid) {
        this.valid = valid;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public LocalDateTime getValidationStartTime() {
        return validationStartTime;
    }
    
    public void setValidationStartTime(LocalDateTime validationStartTime) {
        this.validationStartTime = validationStartTime;
    }
    
    public LocalDateTime getValidationEndTime() {
        return validationEndTime;
    }
    
    public void setValidationEndTime(LocalDateTime validationEndTime) {
        this.validationEndTime = validationEndTime;
    }
    
    public List<ValidationError> getErrors() {
        return errors;
    }
    
    public void setErrors(List<ValidationError> errors) {
        this.errors = errors;
    }
    
    public List<ValidationWarning> getWarnings() {
        return warnings;
    }
    
    public void setWarnings(List<ValidationWarning> warnings) {
        this.warnings = warnings;
    }
    
    public Map<String, ValidationRuleResult> getRuleResults() {
        return ruleResults;
    }
    
    public void setRuleResults(Map<String, ValidationRuleResult> ruleResults) {
        this.ruleResults = ruleResults;
    }
    
    public Map<String, FieldValidationResult> getFieldResults() {
        return fieldResults;
    }
    
    public void setFieldResults(Map<String, FieldValidationResult> fieldResults) {
        this.fieldResults = fieldResults;
    }
    
    public ValidationStatistics getStatistics() {
        return statistics;
    }
    
    public void setStatistics(ValidationStatistics statistics) {
        this.statistics = statistics;
    }
    
    public ValidationLevel getLevel() {
        return level;
    }
    
    public void setLevel(ValidationLevel level) {
        this.level = level;
    }
    
    public Map<String, Object> getContext() {
        return context;
    }
    
    public void setContext(Map<String, Object> context) {
        this.context = context;
    }
    
    /**
     * 验证级别枚举
     */
    public enum ValidationLevel {
        /**
         * 严格模式 - 所有规则都必须通过
         */
        STRICT,
        
        /**
         * 标准模式 - 基本规则必须通过
         */
        STANDARD,
        
        /**
         * 宽松模式 - 只检查关键规则
         */
        LENIENT
    }
    
    /**
     * 验证错误内部类
     */
    public static class ValidationError {
        private String code;
        private String message;
        private String field;
        private String rule;
        private Object value;
        private String severity;
        private Map<String, Object> context;
        
        public ValidationError() {}
        
        public ValidationError(String code, String message, String field) {
            this.code = code;
            this.message = message;
            this.field = field;
            this.severity = "error";
        }
        
        // Getters and Setters
        public String getCode() {
            return code;
        }
        
        public void setCode(String code) {
            this.code = code;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
        
        public String getField() {
            return field;
        }
        
        public void setField(String field) {
            this.field = field;
        }
        
        public String getRule() {
            return rule;
        }
        
        public void setRule(String rule) {
            this.rule = rule;
        }
        
        public Object getValue() {
            return value;
        }
        
        public void setValue(Object value) {
            this.value = value;
        }
        
        public String getSeverity() {
            return severity;
        }
        
        public void setSeverity(String severity) {
            this.severity = severity;
        }
        
        public Map<String, Object> getContext() {
            return context;
        }
        
        public void setContext(Map<String, Object> context) {
            this.context = context;
        }
    }
    
    /**
     * 验证警告内部类
     */
    public static class ValidationWarning {
        private String code;
        private String message;
        private String field;
        private String suggestion;
        private Map<String, Object> context;
        
        public ValidationWarning() {}
        
        public ValidationWarning(String code, String message, String field) {
            this.code = code;
            this.message = message;
            this.field = field;
        }
        
        // Getters and Setters
        public String getCode() {
            return code;
        }
        
        public void setCode(String code) {
            this.code = code;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
        
        public String getField() {
            return field;
        }
        
        public void setField(String field) {
            this.field = field;
        }
        
        public String getSuggestion() {
            return suggestion;
        }
        
        public void setSuggestion(String suggestion) {
            this.suggestion = suggestion;
        }
        
        public Map<String, Object> getContext() {
            return context;
        }
        
        public void setContext(Map<String, Object> context) {
            this.context = context;
        }
    }
    
    /**
     * 验证规则结果内部类
     */
    public static class ValidationRuleResult {
        private String ruleName;
        private boolean passed;
        private String message;
        private Object expectedValue;
        private Object actualValue;
        
        // Getters and Setters
        public String getRuleName() {
            return ruleName;
        }
        
        public void setRuleName(String ruleName) {
            this.ruleName = ruleName;
        }
        
        public boolean isPassed() {
            return passed;
        }
        
        public void setPassed(boolean passed) {
            this.passed = passed;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
        
        public Object getExpectedValue() {
            return expectedValue;
        }
        
        public void setExpectedValue(Object expectedValue) {
            this.expectedValue = expectedValue;
        }
        
        public Object getActualValue() {
            return actualValue;
        }
        
        public void setActualValue(Object actualValue) {
            this.actualValue = actualValue;
        }
    }
    
    /**
     * 字段验证结果内部类
     */
    public static class FieldValidationResult {
        private String fieldName;
        private boolean valid;
        private List<ValidationError> errors;
        private List<ValidationWarning> warnings;
        
        // Getters and Setters
        public String getFieldName() {
            return fieldName;
        }
        
        public void setFieldName(String fieldName) {
            this.fieldName = fieldName;
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public void setValid(boolean valid) {
            this.valid = valid;
        }
        
        public List<ValidationError> getErrors() {
            return errors;
        }
        
        public void setErrors(List<ValidationError> errors) {
            this.errors = errors;
        }
        
        public List<ValidationWarning> getWarnings() {
            return warnings;
        }
        
        public void setWarnings(List<ValidationWarning> warnings) {
            this.warnings = warnings;
        }
    }
    
    /**
     * 验证统计信息内部类
     */
    public static class ValidationStatistics {
        private int totalRules;
        private int passedRules;
        private int failedRules;
        private int totalFields;
        private int validFields;
        private int invalidFields;
        private long validationTimeMillis;
        
        // Getters and Setters
        public int getTotalRules() {
            return totalRules;
        }
        
        public void setTotalRules(int totalRules) {
            this.totalRules = totalRules;
        }
        
        public int getPassedRules() {
            return passedRules;
        }
        
        public void setPassedRules(int passedRules) {
            this.passedRules = passedRules;
        }
        
        public int getFailedRules() {
            return failedRules;
        }
        
        public void setFailedRules(int failedRules) {
            this.failedRules = failedRules;
        }
        
        public int getTotalFields() {
            return totalFields;
        }
        
        public void setTotalFields(int totalFields) {
            this.totalFields = totalFields;
        }
        
        public int getValidFields() {
            return validFields;
        }
        
        public void setValidFields(int validFields) {
            this.validFields = validFields;
        }
        
        public int getInvalidFields() {
            return invalidFields;
        }
        
        public void setInvalidFields(int invalidFields) {
            this.invalidFields = invalidFields;
        }
        
        public long getValidationTimeMillis() {
            return validationTimeMillis;
        }
        
        public void setValidationTimeMillis(long validationTimeMillis) {
            this.validationTimeMillis = validationTimeMillis;
        }
    }
}