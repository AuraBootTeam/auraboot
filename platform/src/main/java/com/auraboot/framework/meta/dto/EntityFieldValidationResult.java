package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;

/**
 * 实体字段验证结果DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityFieldValidationResult {
    
    /**
     * 验证ID
     */
    private String validationId;
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 实体PID
     */
    private String entityPid;
    
    /**
     * 实体名称
     */
    private String entityName;
    
    /**
     * 验证状态
     */
    private ValidationStatus status;
    
    /**
     * 验证是否通过
     */
    private Boolean isValid;
    
    /**
     * 总字段数
     */
    private Integer totalFields;
    
    /**
     * 有效字段数
     */
    private Integer validFields;
    
    /**
     * 无效字段数
     */
    private Integer invalidFields;
    
    /**
     * 警告字段数
     */
    private Integer warningFields;
    
    /**
     * 验证错误列表
     */
    @Builder.Default
    private List<ValidationError> errors = new ArrayList<>();
    
    /**
     * 验证警告列表
     */
    @Builder.Default
    private List<ValidationWarning> warnings = new ArrayList<>();
    
    /**
     * 验证建议列表
     */
    @Builder.Default
    private List<ValidationSuggestion> suggestions = new ArrayList<>();
    
    /**
     * 验证开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 验证结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 验证人
     */
    private String validatedBy;
    
    /**
     * 验证状态枚举
     */
    public enum ValidationStatus {
        PASSED,
        FAILED,
        WARNING,
        IN_PROGRESS
    }
    
    /**
     * 验证错误
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationError {
        private String fieldPid;
        private String code;
        private String fieldName;
        private String errorCode;
        private String errorMessage;
        private String severity;
        private String category;
    }
    
    /**
     * 验证警告
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationWarning {
        private String fieldPid;
        private String code;
        private String fieldName;
        private String warningCode;
        private String warningMessage;
        private String category;
    }
    
    /**
     * 验证建议
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationSuggestion {
        private String fieldPid;
        private String code;
        private String fieldName;
        private String suggestionCode;
        private String suggestionMessage;
        private String category;
        private String priority;
    }
    
    /**
     * 创建通过的验证结果
     */
    public static EntityFieldValidationResult passed(String validationId, String tenantId, String entityPid) {
        return EntityFieldValidationResult.builder()
                .validationId(validationId)
                .tenantId(tenantId)
                .entityPid(entityPid)
                .status(ValidationStatus.PASSED)
                .isValid(true)
                .endTime(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }
    
    /**
     * 创建失败的验证结果
     */
    public static EntityFieldValidationResult failed(String validationId, String tenantId, String entityPid) {
        return EntityFieldValidationResult.builder()
                .validationId(validationId)
                .tenantId(tenantId)
                .entityPid(entityPid)
                .status(ValidationStatus.FAILED)
                .isValid(false)
                .endTime(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }
    
    /**
     * 创建有警告的验证结果
     */
    public static EntityFieldValidationResult warning(String validationId, String tenantId, String entityPid) {
        return EntityFieldValidationResult.builder()
                .validationId(validationId)
                .tenantId(tenantId)
                .entityPid(entityPid)
                .status(ValidationStatus.WARNING)
                .isValid(true)
                .endTime(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }
    
    /**
     * 检查是否有错误
     */
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
    
    /**
     * 检查是否有警告
     */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
    
    /**
     * 检查是否有建议
     */
    public boolean hasSuggestions() {
        return suggestions != null && !suggestions.isEmpty();
    }
    
    /**
     * 获取错误数量
     */
    public int getErrorCount() {
        return errors != null ? errors.size() : 0;
    }
    
    /**
     * 获取警告数量
     */
    public int getWarningCount() {
        return warnings != null ? warnings.size() : 0;
    }
    
    /**
     * 获取建议数量
     */
    public int getSuggestionCount() {
        return suggestions != null ? suggestions.size() : 0;
    }
    
    /**
     * 获取验证通过率
     */
    public double getValidationPassRate() {
        if (totalFields == null || totalFields == 0) {
            return 0.0;
        }
        return (double) (validFields != null ? validFields : 0) / totalFields;
    }
}