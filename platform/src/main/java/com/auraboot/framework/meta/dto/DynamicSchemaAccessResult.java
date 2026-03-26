package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 动态Schema权限结果
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicSchemaAccessResult {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 动态权限映射
     */
    private Map<String, Object> dynamicPermissions;

    /**
     * 计算时间
     */
    private LocalDateTime calculationTime;

    /**
     * 上下文哈希
     */
    private String contextHash;
}
