package com.auraboot.framework.meta.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;

/**
 * 字段排序调整请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class FieldOrderRequest {

    /**
     * 绑定关系ID
     */
    private Long bindingId;

    /**
     * 模型ID
     */
    @NotNull(message = "模型ID不能为空")
    private Long modelId;

    /**
     * 字段ID
     */
    @NotNull(message = "字段ID不能为空")
    private Long fieldId;

    /**
     * 新的排序值
     */
    @NotNull(message = "排序值不能为空")
    private Integer newOrder;

    /**
     * 调整模式
     */
    private AdjustMode adjustMode;

    /**
     * 是否自动调整其他字段的排序
     */
    private Boolean autoAdjust;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public FieldOrderRequest() {
        this.adjustMode = AdjustMode.ABSOLUTE;
        this.autoAdjust = true;
    }

    /**
     * 构造函数
     */
    public FieldOrderRequest(Long modelId, Long fieldId, Integer newOrder) {
        this();
        this.modelId = modelId;
        this.fieldId = fieldId;
        this.newOrder = newOrder;
    }

    /**
     * 调整模式
     */
    public enum AdjustMode {
        /**
         * 绝对位置 - 直接设置为指定的排序值
         */
        ABSOLUTE,

        /**
         * 相对位置 - 在当前位置基础上调整
         */
        RELATIVE,

        /**
         * 插入位置 - 插入到指定位置，其他字段自动调整
         */
        INSERT,

        /**
         * 移动位置 - 移动到指定位置，其他字段自动调整
         */
        MOVE
    }
}