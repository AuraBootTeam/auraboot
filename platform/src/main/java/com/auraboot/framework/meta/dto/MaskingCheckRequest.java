package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotBlank;

/**
 * 脱敏检查请求DTO
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaskingCheckRequest {

    /**
     * 字段名称
     */
    @NotBlank(message = "字段名称不能为空")
    private String fieldName;

    /**
     * 数据类型
     */
    private String dataType;

    /**
     * 字段值（用于动态判断）
     */
    private Object fieldValue;

    /**
     * 上下文信息
     */
    private String context;

    /**
     * 检查级别（BASIC, STANDARD, STRICT）
     */
    private String checkLevel;
}