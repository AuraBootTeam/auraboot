package com.auraboot.framework.meta.dto;

import lombok.Data;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;

import java.util.Map;

/**
 * 字段验证规则更新请求DTO
 * 用于更新字段验证规则的请求参数
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
public class FieldValidationRuleUpdateRequest {

    /**
     * 规则名称
     */
    @Size(max = 100, message = "规则名称长度不能超过100个字符")
    private String ruleName;

    /**
     * 规则描述
     */
    @Size(max = 500, message = "规则描述长度不能超过500个字符")
    private String description;

    /**
     * 规则参数（JSON格式）
     */
    private Map<String, Object> parameters;

    /**
     * 错误消息
     */
    @Size(max = 200, message = "错误消息长度不能超过200个字符")
    private String errorMessage;

    /**
     * 规则优先级
     */
    @Min(value = 0, message = "规则优先级不能为负数")
    private Integer priority;

    /**
     * 是否启用
     */
    private Boolean enabled;
}