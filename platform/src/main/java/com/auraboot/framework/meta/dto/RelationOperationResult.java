package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 关联操作结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class RelationOperationResult {
    
    /**
     * 操作是否成功
     */
    private Boolean success;
    
    /**
     * 操作类型
     */
    private OperationType operationType;
    
    /**
     * 成功处理的记录数
     */
    private Integer successCount;
    
    /**
     * 失败的记录数
     */
    private Integer failedCount;
    
    /**
     * 成功的记录ID列表
     */
    private List<String> successRecordIds;
    
    /**
     * 失败的记录ID列表
     */
    private List<String> failedRecordIds;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    public enum OperationType {
        CREATE_RELATION,
        REMOVE_RELATION,
        UPDATE_RELATION
    }
}