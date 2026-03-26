package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * 字典验证结果DTO
 */
@Data
public class DictValidationResult {

    /**
     * 是否验证通过
     */
    private Boolean valid;

    /**
     * 错误信息列表
     */
    private List<String> errors;

    /**
     * 警告信息列表
     */
    private List<String> warnings;

    /**
     * 验证详情
     */
    private ValidationDetails details;

    /**
     * 验证时间戳
     */
    private Long validationTimestamp;

    /**
     * 验证详情
     */
    @Data
    public static class ValidationDetails {
        
        /**
         * 编码验证结果
         */
        private Boolean codeValid;

        /**
         * 名称验证结果
         */
        private Boolean nameValid;

        /**
         * 数据源配置验证结果
         */
        private Boolean sourceConfigValid;

        /**
         * 级联配置验证结果
         */
        private Boolean cascadeConfigValid;

        /**
         * 缓存配置验证结果
         */
        private Boolean cacheConfigValid;

        /**
         * 版本策略验证结果
         */
        private Boolean versionStrategyValid;

        /**
         * 字典项验证结果
         */
        private Boolean itemsValid;

        /**
         * 依赖关系验证结果
         */
        private Boolean dependenciesValid;
    }

    /**
     * 构造函数
     */
    public DictValidationResult() {
        this.valid = true;
        this.errors = new java.util.ArrayList<>();
        this.warnings = new java.util.ArrayList<>();
        this.details = new ValidationDetails();
        this.validationTimestamp = System.currentTimeMillis();
    }

    /**
     * 添加错误信息
     */
    public void addError(String error) {
        this.valid = false;
        this.errors.add(error);
    }

    /**
     * 添加警告信息
     */
    public void addWarning(String warning) {
        this.warnings.add(warning);
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
}