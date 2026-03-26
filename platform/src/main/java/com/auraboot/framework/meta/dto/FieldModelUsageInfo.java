package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 字段模型使用情况信息DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class FieldModelUsageInfo {

    /**
     * 字段ID
     */
    private Long fieldId;

    /**
     * 字段键
     */
    private String code;

    /**
     * 使用该字段的模型总数
     */
    private Integer totalModels;

    /**
     * 活跃模型数
     */
    private Integer activeModels;

    /**
     * 使用率
     */
    private Double usageRate;

    /**
     * 扩展信息
     */
    private Object extension;
}