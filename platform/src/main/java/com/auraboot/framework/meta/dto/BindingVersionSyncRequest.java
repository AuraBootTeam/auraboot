package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系版本同步请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingVersionSyncRequest {

    /**
     * 模型ID
     */
    private Long modelId;

    /**
     * 字段ID
     */
    private Long fieldId;

    /**
     * 目标版本
     */
    private Integer targetVersion;

    /**
     * 同步模式
     */
    private SyncMode syncMode;

    /**
     * 是否强制同步
     */
    private Boolean forceSync;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingVersionSyncRequest() {
        this.syncMode = SyncMode.AUTO;
        this.forceSync = false;
    }

    /**
     * 同步模式
     */
    public enum SyncMode {
        /**
         * 自动同步
         */
        AUTO,

        /**
         * 手动同步
         */
        MANUAL,

        /**
         * 强制同步
         */
        FORCE
    }
}