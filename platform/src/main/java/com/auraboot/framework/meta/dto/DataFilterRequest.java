package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据过滤请求DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
public class DataFilterRequest {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 模型编码
     */
    private String modelCode;

    /**
     * 待过滤的数据
     */
    private List<Map<String, Object>> data;

    /**
     * 过滤上下文
     */
    private Map<String, Object> context;

    /**
     * 是否启用数据脱敏
     */
    @Builder.Default
    private Boolean enableMasking = true;

    /**
     * 是否启用字段过滤
     */
    @Builder.Default
    private Boolean enableFieldFilter = true;

    /**
     * 是否记录访问日志
     */
    @Builder.Default
    private Boolean enableAccessLog = true;
}