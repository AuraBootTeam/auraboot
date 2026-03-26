package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 批量创建结果
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BatchCreateResult extends AbstractResponse {
    
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
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误消息
     */
    private String message;
    
    /**
     * 判断操作是否成功
     */
    public boolean isSuccess() {
        return success != null && success;
    }
}