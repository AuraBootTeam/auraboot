package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 业务规则
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class BusinessRule {
    
    /**
     * 规则名称
     */
    private String name;
    
    /**
     * 规则表达式
     */
    private String expression;
    
    /**
     * 错误消息
     */
    private String message;
    
    /**
     * 规则严重性
     */
    private Severity severity;
    
    /**
     * 是否启用
     */
    private boolean enabled;
    
    /**
     * 规则严重性枚举
     */
    public enum Severity {
        ERROR, WARNING, INFO
    }
}