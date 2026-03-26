package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模式操作结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class SchemaOperationResult {
    
    /**
     * 操作是否成功
     */
    private Boolean success;
    
    /**
     * 操作类型
     */
    private SchemaOperationType operationType;
    
    /**
     * 模型编码
     */
    private String modelCode;
    
    /**
     * 表名
     */
    private String tableName;
    
    /**
     * 执行的DDL语句
     */
    private List<String> executedDDL;
    
    /**
     * 操作消息
     */
    private String message;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 操作时间
     */
    private LocalDateTime operationTime;
    
    /**
     * 影响的字段
     */
    private List<String> affectedFields;
    
    /**
     * 操作统计
     */
    private SchemaOperationStats stats;
    
    // 便利方法
    public boolean isSuccess() {
        return Boolean.TRUE.equals(success);
    }
    
    public enum SchemaOperationType {
        CREATE_TABLE,
        UPDATE_TABLE,
        DROP_TABLE,
        ADD_FIELD,
        REMOVE_FIELD,
        UPDATE_FIELD,
        CREATE_INDEX,
        DROP_INDEX,
        SYNC_SCHEMA
    }
}