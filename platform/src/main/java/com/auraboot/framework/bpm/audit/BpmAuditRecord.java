package com.auraboot.framework.bpm.audit;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * BPM审计记录数据模型
 * 
 * @author AuraBoot Team
 */
@Data
@Builder
public class BpmAuditRecord {
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 操作类型
     */
    private String operation;
    
    /**
     * 流程实例ID
     */
    private String processInstanceId;
    
    /**
     * 任务ID
     */
    private String taskId;
    
    /**
     * 流程定义Key
     */
    private String processDefinitionKey;
    
    /**
     * 版本号
     */
    private Integer version;
    
    /**
     * 操作详情
     */
    private Map<String, Object> details;
    
    /**
     * 操作时间
     */
    private Instant timestamp;
    
    /**
     * IP地址
     */
    private String ipAddress;
    
    /**
     * 操作结果（成功/失败）
     */
    private String result;
    
    /**
     * 错误信息（如果操作失败）
     */
    private String errorMessage;
}
