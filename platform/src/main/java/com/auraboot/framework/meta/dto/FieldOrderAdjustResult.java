package com.auraboot.framework.meta.dto;

import lombok.Data;
import java.util.List;
import java.util.ArrayList;

/**
 * 字段排序调整结果DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class FieldOrderAdjustResult {

    /**
     * 操作是否成功
     */
    private Boolean success;

    /**
     * 操作消息
     */
    private String message;

    /**
     * 受影响的绑定关系数量
     */
    private Integer affectedCount;

    /**
     * 调整后的字段排序信息列表
     */
    private List<FieldOrderInfo> adjustedOrders;

    /**
     * 处理时间（毫秒）
     */
    private Long processingTime;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public FieldOrderAdjustResult() {
        this.success = true;
        this.affectedCount = 0;
        this.adjustedOrders = new ArrayList<>();
    }

    /**
     * 构造成功结果
     */
    public static FieldOrderAdjustResult success(String message) {
        FieldOrderAdjustResult result = new FieldOrderAdjustResult();
        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    /**
     * 构造失败结果
     */
    public static FieldOrderAdjustResult failure(String message) {
        FieldOrderAdjustResult result = new FieldOrderAdjustResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }

    /**
     * 添加调整后的排序信息
     */
    public void addAdjustedOrder(FieldOrderInfo orderInfo) {
        this.adjustedOrders.add(orderInfo);
        this.affectedCount++;
    }
}