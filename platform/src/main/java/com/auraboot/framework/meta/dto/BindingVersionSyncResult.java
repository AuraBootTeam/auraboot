package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系版本同步结果DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingVersionSyncResult {

    /**
     * 同步是否成功
     */
    private Boolean success;

    /**
     * 同步消息
     */
    private String message;

    /**
     * 同步的绑定关系数量
     */
    private Integer syncedCount;

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
    public BindingVersionSyncResult() {
        this.success = true;
        this.syncedCount = 0;
    }
}