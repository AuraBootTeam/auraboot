package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量操作结果抽象基类
 * 包含批量操作的通用字段，用于减少代码重复
 * 
 * @author AuraBoot
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public abstract class BatchOperationResult {
    
    /**
     * 总数量
     */
    private Integer totalCount;
    
    /**
     * 成功数量
     */
    private Integer successCount;
    
    /**
     * 失败数量
     */
    private Integer failureCount;
    
    /**
     * 成功的ID列表
     */
    private List<String> successIds;
    
    /**
     * 失败原因列表
     */
    private List<String> failureReasons;
    
    /**
     * 错误信息列表
     */
    private List<String> errors;
}