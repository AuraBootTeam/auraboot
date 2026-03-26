package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 实体验证结果DTO
 */
@Data
@Schema(description = "实体验证结果DTO")
public class EntityValidationResult {

    @Schema(description = "验证ID")
    private String validationId;

    @Schema(description = "实体PID")
    private String entityPid;

    @Schema(description = "验证状态")
    private ValidationStatus status;

    @Schema(description = "验证通过")
    private Boolean isValid;

    @Schema(description = "验证分数（0-100）")
    private Integer validationScore;

    @Schema(description = "验证错误列表")
    private List<ValidationError> errors;

    @Schema(description = "验证警告列表")
    private List<ValidationWarning> warnings;

    @Schema(description = "验证信息列表")
    private List<ValidationInfo> infos;

    @Schema(description = "验证规则统计")
    private Map<String, Integer> ruleStatistics;

    @Schema(description = "验证时间")
    private LocalDateTime validationTime;

    @Schema(description = "验证耗时（毫秒）")
    private Long duration;

    @Schema(description = "验证人")
    private String validatedBy;

    @Schema(description = "验证配置")
    private Map<String, Object> validationConfig;

    /**
     * 验证状态枚举
     */
    public enum ValidationStatus {
        PENDING("待验证"),
        IN_PROGRESS("验证中"),
        COMPLETED("验证完成"),
        FAILED("验证失败"),
        CANCELLED("验证取消");

        private final String description;

        ValidationStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 验证错误
     */
    @Data
    @Schema(description = "验证错误")
    public static class ValidationError {
        @Schema(description = "错误代码")
        private String errorCode;
        
        @Schema(description = "错误消息")
        private String errorMessage;
        
        @Schema(description = "错误级别")
        private ErrorLevel level;
        
        @Schema(description = "字段路径")
        private String fieldPath;
        
        @Schema(description = "错误值")
        private Object errorValue;
        
        @Schema(description = "建议修复方案")
        private String suggestion;
        
        @Schema(description = "错误详情")
        private Map<String, Object> details;
    }

    /**
     * 验证警告
     */
    @Data
    @Schema(description = "验证警告")
    public static class ValidationWarning {
        @Schema(description = "警告代码")
        private String warningCode;
        
        @Schema(description = "警告消息")
        private String warningMessage;
        
        @Schema(description = "字段路径")
        private String fieldPath;
        
        @Schema(description = "警告值")
        private Object warningValue;
        
        @Schema(description = "建议")
        private String suggestion;
    }

    /**
     * 验证信息
     */
    @Data
    @Schema(description = "验证信息")
    public static class ValidationInfo {
        @Schema(description = "信息代码")
        private String infoCode;
        
        @Schema(description = "信息消息")
        private String infoMessage;
        
        @Schema(description = "字段路径")
        private String fieldPath;
        
        @Schema(description = "信息详情")
        private Map<String, Object> details;
    }

    /**
     * 错误级别枚举
     */
    public enum ErrorLevel {
        CRITICAL("严重"),
        HIGH("高"),
        MEDIUM("中"),
        LOW("低");

        private final String description;

        ErrorLevel(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 创建成功的验证结果
     */
    public static EntityValidationResult success(String entityPid) {
        EntityValidationResult result = new EntityValidationResult();
        result.setEntityPid(entityPid);
        result.setStatus(ValidationStatus.COMPLETED);
        result.setIsValid(true);
        result.setValidationScore(100);
        result.setValidationTime(LocalDateTime.now(ZoneOffset.UTC));
        return result;
    }

    /**
     * 创建失败的验证结果
     */
    public static EntityValidationResult failure(String entityPid, List<ValidationError> errors) {
        EntityValidationResult result = new EntityValidationResult();
        result.setEntityPid(entityPid);
        result.setStatus(ValidationStatus.COMPLETED);
        result.setIsValid(false);
        result.setErrors(errors);
        result.setValidationTime(LocalDateTime.now(ZoneOffset.UTC));
        return result;
    }
}