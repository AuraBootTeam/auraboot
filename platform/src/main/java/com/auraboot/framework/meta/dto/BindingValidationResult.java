package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系验证结果DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingValidationResult {

    /**
     * 验证是否通过
     */
    private Boolean valid;

    /**
     * 验证消息
     */
    private String message;

    /**
     * 验证的绑定关系数量
     */
    private Integer validatedCount;

    /**
     * 通过验证的数量
     */
    private Integer passedCount;

    /**
     * 失败的数量
     */
    private Integer failedCount;

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
    public BindingValidationResult() {
        this.valid = true;
        this.validatedCount = 0;
        this.passedCount = 0;
        this.failedCount = 0;
    }
}