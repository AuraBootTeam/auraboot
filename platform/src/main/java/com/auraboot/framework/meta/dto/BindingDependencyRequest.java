package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系依赖分析请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingDependencyRequest {

    /**
     * 模型ID
     */
    private Long modelId;

    /**
     * 字段ID
     */
    private Long fieldId;

    /**
     * 分析深度
     */
    private Integer depth;

    /**
     * 是否包含详细信息
     */
    private Boolean includeDetails;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingDependencyRequest() {
        this.depth = 1;
        this.includeDetails = true;
    }
}