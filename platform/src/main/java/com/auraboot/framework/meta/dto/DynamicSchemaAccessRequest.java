package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 动态Schema权限请求
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicSchemaAccessRequest {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * Schema PID
     */
    private String schemaPid;

    /**
     * 上下文信息
     */
    private Map<String, Object> context;
}
