package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 页面配置验证结果DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageValidationResult {
    
    /**
     * 页面Key
     */
    private String pageKey;
    
    /**
     * 页面版本
     */
    private String version;
    
    /**
     * 验证是否通过
     */
    @Builder.Default
    private Boolean valid = true;
    
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
     * 验证时间
     */
    @Builder.Default
    private LocalDateTime validationTime = LocalDateTime.now(ZoneOffset.UTC);
    
    /**
     * 验证耗时（毫秒）
     */
    private Long validationDuration;
    
    /**
     * 验证统计信息
     */
    @Builder.Default
    private Map<String, Object> statistics = new HashMap<>();
    
    /**
     * 验证上下文
     */
    @Builder.Default
    private Map<String, Object> context = new HashMap<>();
    
    /**
     * 添加验证错误
     */
    public void addError(String field, String code, String message) {
        if (this.errors == null) {
            this.errors = new ArrayList<>();
        }
        ValidationError error = ValidationError.builder()
                .field(field)
                .code(code)
                .message(message)
                .build();
        this.errors.add(error);
        this.valid = false;
    }
    
    /**
     * 添加验证错误
     */
    public void addError(ValidationError error) {
        if (this.errors == null) {
            this.errors = new ArrayList<>();
        }
        this.errors.add(error);
        this.valid = false;
    }
    
    /**
     * 添加验证警告
     */
    public void addWarning(String field, String code, String message) {
        if (this.warnings == null) {
            this.warnings = new ArrayList<>();
        }
        ValidationWarning warning = ValidationWarning.builder()
                .field(field)
                .code(code)
                .message(message)
                .build();
        this.warnings.add(warning);
    }
    
    /**
     * 添加验证警告
     */
    public void addWarning(ValidationWarning warning) {
        if (this.warnings == null) {
            this.warnings = new ArrayList<>();
        }
        this.warnings.add(warning);
    }
    
    /**
     * 添加验证建议
     */
    public void addSuggestion(String field, String code, String message) {
        if (this.suggestions == null) {
            this.suggestions = new ArrayList<>();
        }
        ValidationSuggestion suggestion = ValidationSuggestion.builder()
                .field(field)
                .code(code)
                .message(message)
                .build();
        this.suggestions.add(suggestion);
    }
    
    /**
     * 添加验证建议
     */
    public void addSuggestion(ValidationSuggestion suggestion) {
        if (this.suggestions == null) {
            this.suggestions = new ArrayList<>();
        }
        this.suggestions.add(suggestion);
    }
    
    /**
     * 添加统计信息
     */
    public void addStatistic(String code, Object value) {
        if (this.statistics == null) {
            this.statistics = new HashMap<>();
        }
        this.statistics.put(code, value);
    }
    
    /**
     * 添加上下文信息
     */
    public void addContext(String code, Object value) {
        if (this.context == null) {
            this.context = new HashMap<>();
        }
        this.context.put(code, value);
    }
    
    /**
     * 获取错误数量
     */
    public int getErrorCount() {
        return this.errors != null ? this.errors.size() : 0;
    }
    
    /**
     * 获取警告数量
     */
    public int getWarningCount() {
        return this.warnings != null ? this.warnings.size() : 0;
    }
    
    /**
     * 获取建议数量
     */
    public int getSuggestionCount() {
        return this.suggestions != null ? this.suggestions.size() : 0;
    }
    
    /**
     * 验证错误
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValidationError {
        /**
         * 字段名
         */
        private String field;
        
        /**
         * 错误代码
         */
        private String code;
        
        /**
         * 错误消息
         */
        private String message;
        
        /**
         * 错误级别
         */
        @Builder.Default
        private String level = "error";
        
        /**
         * 错误位置
         */
        private String location;
        
        /**
         * 修复建议
         */
        private String fixSuggestion;
    }
    
    /**
     * 验证警告
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValidationWarning {
        /**
         * 字段名
         */
        private String field;
        
        /**
         * 警告代码
         */
        private String code;
        
        /**
         * 警告消息
         */
        private String message;
        
        /**
         * 警告级别
         */
        @Builder.Default
        private String level = "warning";
        
        /**
         * 警告位置
         */
        private String location;
        
        /**
         * 改进建议
         */
        private String improvementSuggestion;
    }
    
    /**
     * 验证建议
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValidationSuggestion {
        /**
         * 字段名
         */
        private String field;
        
        /**
         * 建议代码
         */
        private String code;
        
        /**
         * 建议消息
         */
        private String message;
        
        /**
         * 建议级别
         */
        @Builder.Default
        private String level = "suggestion";
        
        /**
         * 建议位置
         */
        private String location;
        
        /**
         * 优化效果
         */
        private String benefit;
    }
}