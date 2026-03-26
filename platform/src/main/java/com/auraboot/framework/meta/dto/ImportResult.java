package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;

/**
 * 导入结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class ImportResult {
    
    /**
     * 导入是否成功
     */
    private Boolean success;
    
    /**
     * 总记录数
     */
    private Integer totalCount;
    
    /**
     * 成功导入数
     */
    private Integer successCount;
    
    /**
     * 失败数
     */
    private Integer failedCount;
    
    /**
     * 跳过数
     */
    private Integer skippedCount;
    
    /**
     * 导入时间
     */
    private Instant importTime;
    
    /**
     * 错误详情
     */
    private List<ImportError> errors;
    
    /**
     * 警告信息
     */
    private List<String> warnings;
    
    /**
     * 导入摘要
     */
    private String summary;
    
    @Data
    @Builder
    public static class ImportError {
        private Integer rowNumber;
        private String fieldName;
        private String errorMessage;
        private Object rejectedValue;
    }
}