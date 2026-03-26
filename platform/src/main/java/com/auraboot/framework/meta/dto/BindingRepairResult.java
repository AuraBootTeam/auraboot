package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系修复结果DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingRepairResult {

    /**
     * 修复是否成功
     */
    private Boolean success;

    /**
     * 修复消息
     */
    private String message;

    /**
     * 修复的绑定关系数量
     */
    private Integer repairedCount;

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
    public BindingRepairResult() {
        this.success = true;
        this.repairedCount = 0;
    }
}