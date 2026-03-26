package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * 字典项验证结果DTO
 * 用于字典项配置验证的结果返回
 */
@Data
public class DictItemValidationResult {

    /**
     * 是否有效
     */
    private Boolean valid;

    /**
     * 错误信息列表
     */
    private List<ValidationError> errors;

    /**
     * 警告信息列表
     */
    private List<ValidationWarning> warnings;

    /**
     * 验证时间戳
     */
    private Long validationTimestamp;

    /**
     * 构造函数
     */
    public DictItemValidationResult() {
        this.valid = true;
        this.validationTimestamp = System.currentTimeMillis();
    }

    /**
     * 添加错误
     * @param field 字段名
     * @param message 错误信息
     */
    public void addError(String field, String message) {
        if (this.errors == null) {
            this.errors = new java.util.ArrayList<>();
        }
        this.errors.add(new ValidationError(field, message));
        this.valid = false;
    }

    /**
     * 添加警告
     * @param field 字段名
     * @param message 警告信息
     */
    public void addWarning(String field, String message) {
        if (this.warnings == null) {
            this.warnings = new java.util.ArrayList<>();
        }
        this.warnings.add(new ValidationWarning(field, message));
    }

    /**
     * 是否有错误
     * @return 是否有错误
     */
    public boolean hasErrors() {
        return this.errors != null && !this.errors.isEmpty();
    }

    /**
     * 是否有警告
     * @return 是否有警告
     */
    public boolean hasWarnings() {
        return this.warnings != null && !this.warnings.isEmpty();
    }
}