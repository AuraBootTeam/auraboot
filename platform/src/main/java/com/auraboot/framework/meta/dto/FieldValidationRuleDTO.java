package com.auraboot.framework.meta.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 字段验证规则DTO
 * 用于字段验证规则的数据传输
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
public class FieldValidationRuleDTO {

    /**
     * 业务主键
     */
    private String pid;

    /**
     * 所属字段PID
     */
    private String fieldPid;

    /**
     * 规则类型：REQUIRED, MIN_LENGTH, MAX_LENGTH, PATTERN, RANGE, CUSTOM等
     */
    private String ruleType;

    /**
     * 规则名称
     */
    private String ruleName;

    /**
     * 规则描述
     */
    private String description;

    /**
     * 规则参数（JSON格式）
     */
    private Map<String, Object> parameters;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 规则优先级
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
}