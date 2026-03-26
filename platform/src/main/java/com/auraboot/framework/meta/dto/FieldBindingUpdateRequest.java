package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 字段绑定更新请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class FieldBindingUpdateRequest {

    /**
     * 字段排序
     */
    private Integer fieldOrder;

    /**
     * 是否必填
     */
    private Boolean required;

    /**
     * 是否只读
     */
    private Boolean readonly;

    /**
     * 是否可见
     */
    private Boolean visible;

    /**
     * 绑定配置
     */
    private Object bindingConfig;

    /**
     * 扩展信息
     */
    private Object extension;
}