package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 实体导入结果DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityImportResult {
    
    /**
     * 导入是否成功
     */
    private Boolean success;
    
    /**
     * 成功导入的实体数量
     */
    private Integer successCount;
    
    /**
     * 失败的实体数量
     */
    private Integer failureCount;
    
    /**
     * 成功导入的实体列表
     */
    private List<String> successEntities;
    
    /**
     * 失败的实体列表及错误信息
     */
    private List<ImportError> failureEntities;
    
    /**
     * 导入过程中的警告信息
     */
    private List<String> warnings;
    
    /**
     * 导入摘要信息
     */
    private String summary;
    
    /**
     * 导入错误详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportError {
        /**
         * 实体编码
         */
        private String entityCode;
        
        /**
         * 错误信息
         */
        private String errorMessage;
        
        /**
         * 错误类型
         */
        private String errorType;
    }
}