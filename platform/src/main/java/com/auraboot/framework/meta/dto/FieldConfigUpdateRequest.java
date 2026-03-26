package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 字段配置更新请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.1.0
 */
@Data
public class FieldConfigUpdateRequest {

    /**
     * 字段顺序
     */
    @Min(value = 0, message = "字段顺序不能小于0")
    private Integer fieldOrder;

    /**
     * 是否必填
     */
    private Boolean required;

    /**
     * 是否可见
     */
    private Boolean visible;

    /**
     * 是否可编辑
     */
    private Boolean editable;

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