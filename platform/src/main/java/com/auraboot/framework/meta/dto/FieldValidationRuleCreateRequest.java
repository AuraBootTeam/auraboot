package com.auraboot.framework.meta.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;

import java.util.Map;

/**
 * 字段验证规则创建请求DTO
 * 用于创建字段验证规则的请求参数
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
public class FieldValidationRuleCreateRequest {

    /**
     * 规则类型：REQUIRED, MIN_LENGTH, MAX_LENGTH, PATTERN, RANGE, CUSTOM等
     */
    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    /**
     * 规则名称
     */
    @NotBlank(message = "规则名称不能为空")
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
    @NotBlank(message = "错误消息不能为空")
    @Size(max = 200, message = "错误消息长度不能超过200个字符")
    private String errorMessage;

    /**
     * 规则优先级
     */
    @NotNull(message = "规则优先级不能为空")
    @Min(value = 0, message = "规则优先级不能为负数")
    private Integer priority = 0;

    /**
     * 是否启用
     */
    @NotNull(message = "是否启用不能为空")
    private Boolean enabled = true;
}