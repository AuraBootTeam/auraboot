package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 数据脱敏请求DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
public class DataMaskingRequest {

    /**
     * 字段键
     */
    private String code;

    /**
     * 原始值
     */
    private Object value;

    /**
     * 脱敏规则
     */
    private String maskingRule;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 租户ID
     */
    private Long tenantId;
}