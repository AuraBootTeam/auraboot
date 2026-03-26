package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系依赖分析结果DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingDependencyAnalysis {

    /**
     * 分析是否成功
     */
    private Boolean success;

    /**
     * 分析消息
     */
    private String message;

    /**
     * 依赖关系数量
     */
    private Integer dependencyCount;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingDependencyAnalysis() {
        this.success = true;
        this.dependencyCount = 0;
    }
}