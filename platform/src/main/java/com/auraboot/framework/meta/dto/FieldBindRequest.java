package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 字段绑定请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.1.0
 */
@Data
public class FieldBindRequest {

    /**
     * 字段顺序
     */
    @Min(value = 0, message = "字段顺序不能小于0")
    private Integer fieldOrder = 0;

    /**
     * 是否必填
     */
    private Boolean required = false;

    /**
     * 是否可见
     */
    private Boolean visible = true;

    /**
     * 是否可编辑
     */
    private Boolean editable = true;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 验证规则
     */
    private String validationRules;

    /**
     * 显示配置
     */
    private String displayConfig;

    /**
     * 备注
     */
    private String remarks;
}