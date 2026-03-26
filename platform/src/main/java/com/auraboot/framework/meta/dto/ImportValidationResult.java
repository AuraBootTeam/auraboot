package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导入验证结果DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportValidationResult {
    
    /**
     * 验证是否通过
     */
    private Boolean valid;
    
    /**
     * 总记录数
     */
    private Integer totalCount;
    
    /**
     * 验证通过的记录数量
     */
    private Integer validCount;
    
    /**
     * 验证失败的记录数量
     */
    private Integer invalidCount;
    
    /**
     * 警告记录数量
     */
    private Integer warningCount;
    
    /**
     * 验证通过的记录ID列表
     */
    private List<String> validRecords;
    
    /**
     * 验证失败的记录列表及错误信息
     */
    private List<ValidationError> invalidRecords;
    
    /**
     * 有警告的记录列表
     */
    private List<ValidationWarning> warningRecords;
    
    /**
     * 验证过程中的全局警告信息
     */
    private List<String> globalWarnings;
    
    /**
     * 验证摘要信息
     */
    private String summary;
    
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
    private Long duration;
    
    /**
     * 验证错误详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationError {
        /**
         * 记录行号
         */
        private Integer rowNumber;
        
        /**
         * 记录ID或标识
         */
        private String recordId;
        
        /**
         * 错误字段
         */
        private String fieldName;
        
        /**
         * 字段值
         */
        private Object fieldValue;
        
        /**
         * 错误信息
         */
        private String errorMessage;
        
        /**
         * 错误类型
         */
        private String errorType;
        
        /**
         * 错误级别
         */
        private String errorLevel;
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
         * 记录行号
         */
        private Integer rowNumber;
        
        /**
         * 记录ID或标识
         */
        private String recordId;
        
        /**
         * 警告字段
         */
        private String fieldName;
        
        /**
         * 字段值
         */
        private Object fieldValue;
        
        /**
         * 警告信息
         */
        private String warningMessage;
        
        /**
         * 警告类型
         */
        private String warningType;
        
        /**
         * 建议操作
         */
        private String suggestedAction;
    }
}