package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 字段过滤请求DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
public class FieldFilterRequest {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 字段列表
     */
    private List<String> fields;

    /**
     * 模型编码
     */
    private String modelCode;

    /**
     * 过滤上下文
     */
    private Map<String, Object> context;

    /**
     * 是否包含脱敏规则
     */
    @Builder.Default
    private Boolean includeMaskingRules = true;
}