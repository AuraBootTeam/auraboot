package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量导入结果DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchImportResult {
    
    /**
     * 批量导入是否成功
     */
    private Boolean success;
    
    /**
     * 总记录数
     */
    private Integer totalCount;
    
    /**
     * 成功导入的记录数量
     */
    private Integer successCount;
    
    /**
     * 失败的记录数量
     */
    private Integer failureCount;
    
    /**
     * 跳过的记录数量
     */
    private Integer skippedCount;
    
    /**
     * 成功导入的记录ID列表
     */
    private List<String> successRecords;
    
    /**
     * 失败的记录列表及错误信息
     */
    private List<ImportError> failureRecords;
    
    /**
     * 跳过的记录列表及原因
     */
    private List<SkippedRecord> skippedRecords;
    
    /**
     * 导入过程中的警告信息
     */
    private List<String> warnings;
    
    /**
     * 导入摘要信息
     */
    private String summary;
    
    /**
     * 导入开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 导入结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 导入耗时（毫秒）
     */
    private Long duration;
    
    /**
     * 导入错误详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportError {
        /**
         * 记录行号
         */
        private Integer rowNumber;
        
        /**
         * 记录ID或标识
         */
        private String recordId;
        
        /**
         * 错误信息
         */
        private String errorMessage;
        
        /**
         * 错误类型
         */
        private String errorType;
        
        /**
         * 错误字段
         */
        private String errorField;
    }
    
    /**
     * 跳过记录详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkippedRecord {
        /**
         * 记录行号
         */
        private Integer rowNumber;
        
        /**
         * 记录ID或标识
         */
        private String recordId;
        
        /**
         * 跳过原因
         */
        private String reason;
        
        /**
         * 跳过类型
         */
        private String skipType;
    }
}