package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 业务模型更新请求DTO
 * 用于更新业务模型的参数封装
 */
@Data
public class MetaModelUpdateRequest {

    /**
     * 显示名称
     */
    @NotBlank(message = "显示名称不能为空")
    private String displayName;

    /**
     * 描述信息
     */
    private String description;

    /**
     * 模型类型（ENTITY/VIEW/AGGREGATE等）
     */
    private String modelType;

    /**
     * 扩展属性
     */
    private Map<String, Object> extension;

    /**
     * 版本说明
     */
    private String versionNote;
}