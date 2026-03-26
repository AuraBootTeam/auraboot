package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量模式操作结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class BatchSchemaOperationResult {
    
    /**
     * 整体操作是否成功
     */
    private Boolean success;
    
    /**
     * 总数
     */
    private Integer total;
    
    /**
     * 成功数
     */
    private Integer successCount;
    
    /**
     * 失败数
     */
    private Integer failedCount;
    
    /**
     * 成功的操作结果列表
     */
    private List<SchemaOperationResult> successResults;
    
    /**
     * 失败的操作结果列表
     */
    private List<SchemaOperationResult> failedResults;
    
    /**
     * 操作时间
     */
    private LocalDateTime operationTime;
    
    /**
     * 总耗时（毫秒）
     */
    private Long totalDuration;
    
    /**
     * 操作摘要
     */
    private String summary;
}