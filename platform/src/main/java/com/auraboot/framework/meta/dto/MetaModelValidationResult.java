package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.ArrayList;

/**
 * 模型配置验证结果DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaModelValidationResult {

    /**
     * 验证是否通过
     */
    private Boolean valid;

    /**
     * 错误信息列表
     */
    @Builder.Default
    private List<ValidationError> errors = new ArrayList<>();

    /**
     * 警告信息列表
     */
    @Builder.Default
    private List<ValidationWarning> warnings = new ArrayList<>();

    /**
     * 验证的模型PID
     */
    private String modelPid;

    /**
     * 验证时间戳
     */
    private Long validatedAt;

    /**
     * 验证错误详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationError {
        /**
         * 错误代码
         */
        private String code;

        /**
         * 错误消息
         */
        private String message;

        /**
         * 错误字段路径
         */
        private String fieldPath;

        /**
         * 错误级别
         */
        private String level;
    }

    /**
     * 验证警告详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationWarning {
        /**
         * 警告代码
         */
        private String code;

        /**
         * 警告消息
         */
        private String message;

        /**
         * 警告字段路径
         */
        private String fieldPath;

        /**
         * 建议操作
         */
        private String suggestion;
    }

    /**
     * 添加错误
     * @param code 错误代码
     * @param message 错误消息
     * @param fieldPath 字段路径
     */
    public void addError(String code, String message, String fieldPath) {
        if (errors == null) {
            errors = new ArrayList<>();
        }
        errors.add(ValidationError.builder()
            .code(code)
            .message(message)
            .fieldPath(fieldPath)
            .level("error")
            .build());
        this.valid = false;
    }

    /**
     * 添加警告
     * @param code 警告代码
     * @param message 警告消息
     * @param fieldPath 字段路径
     * @param suggestion 建议操作
     */
    public void addWarning(String code, String message, String fieldPath, String suggestion) {
        if (warnings == null) {
            warnings = new ArrayList<>();
        }
        warnings.add(ValidationWarning.builder()
            .code(code)
            .message(message)
            .fieldPath(fieldPath)
            .suggestion(suggestion)
            .build());
    }

    /**
     * 检查是否有错误
     * @return 是否有错误
     */
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    /**
     * 检查是否有警告
     * @return 是否有警告
     */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    /**
     * 获取错误数量
     * @return 错误数量
     */
    public int getErrorCount() {
        return errors != null ? errors.size() : 0;
    }

    /**
     * 获取警告数量
     * @return 警告数量
     */
    public int getWarningCount() {
        return warnings != null ? warnings.size() : 0;
    }
}