package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系复制选项DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingCopyOptions {

    /**
     * 是否复制字段排序
     */
    private Boolean copyOrder;

    /**
     * 是否复制字段配置
     */
    private Boolean copyConfig;

    /**
     * 是否覆盖现有绑定
     */
    private Boolean overwrite;

    /**
     * 复制模式
     */
    private CopyMode copyMode;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingCopyOptions() {
        this.copyOrder = true;
        this.copyConfig = true;
        this.overwrite = false;
        this.copyMode = CopyMode.SELECTIVE;
    }

    /**
     * 复制模式
     */
    public enum CopyMode {
        ALL,        // 复制所有
        SELECTIVE,  // 选择性复制
        MINIMAL     // 最小复制
    }
}