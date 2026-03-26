package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系复制结果DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingCopyResult {

    /**
     * 复制是否成功
     */
    private Boolean success;

    /**
     * 复制消息
     */
    private String message;

    /**
     * 复制的绑定关系数量
     */
    private Integer copiedCount;

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
    public BindingCopyResult() {
        this.success = true;
        this.copiedCount = 0;
    }
}