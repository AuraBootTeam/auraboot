package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 模型字段使用情况信息DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class ModelFieldUsageInfo {

    /**
     * 模型ID
     */
    private Long modelId;

    /**
     * 模型编码
     */
    private String modelCode;

    /**
     * 绑定字段总数
     */
    private Integer totalFields;

    /**
     * 活跃字段数
     */
    private Integer activeFields;

    /**
     * 使用率
     */
    private Double usageRate;

    /**
     * 扩展信息
     */
    private Object extension;
}