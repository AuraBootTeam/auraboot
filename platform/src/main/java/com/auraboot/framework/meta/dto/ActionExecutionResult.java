package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

/**
 * 操作执行结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class ActionExecutionResult {
    
    /**
     * 执行是否成功
     */
    private Boolean success;
    
    /**
     * 操作名称
     */
    private String actionName;
    
    /**
     * 执行结果数据
     */
    private Map<String, Object> resultData;
    
    /**
     * 执行消息
     */
    private String message;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 执行时间
     */
    private Instant executionTime;
    
    /**
     * 执行耗时（毫秒）
     */
    private Long duration;
}