package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 字段排序信息DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class FieldOrderInfo {

    /**
     * 绑定ID
     */
    private Long bindingId;

    /**
     * 字段ID
     */
    private Long fieldId;

    /**
     * 字段编码
     */
    private String fieldCode;

    /**
     * 字段名称
     */
    private String fieldName;

    /**
     * 当前排序
     */
    private Integer currentOrder;

    /**
     * 新排序
     */
    private Integer newOrder;

    /**
     * 是否已变更
     */
    private Boolean changed = false;

    /**
     * 字段类型
     */
    private String fieldType;

    /**
     * 是否必填
     */
    private Boolean required;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 构造函数
     */
    public FieldOrderInfo() {
        this.changed = false;
    }

    /**
     * 构造函数
     */
    public FieldOrderInfo(Long fieldId, String fieldCode, Integer currentOrder, Integer newOrder) {
        this();
        this.fieldId = fieldId;
        this.fieldCode = fieldCode;
        this.currentOrder = currentOrder;
        this.newOrder = newOrder;
        this.changed = !currentOrder.equals(newOrder);
    }
}