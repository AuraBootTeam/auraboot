package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 验证规则DTO
 */
@Data
public class ValidationRuleDTO {
    
    /**
     * 业务主键
     */
    private String pid;
    
    /**
     * 租户ID
     */
    private String tenantId;
    
      
    
    
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 规则名称
     */
    private String ruleName;
    
    /**
     * 规则类型
     */
    private String ruleType;
    
    /**
     * 规则表达式
     */
    private String ruleExpression;
    
    /**
     * 错误消息（多语言）
     */
    private Map<String, String> errormessage;
    
    /**
     * 规则参数
     */
    private Map<String, Object> ruleParams;
    
    /**
     * 优先级
     */
    private Integer priority;
    
    /**
     * 是否启用
     */
    private Boolean enabled;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    /**
     * 创建人
     */
    private String createdBy;
    
    /**
     * 更新人
     */
    private String updatedBy;
}