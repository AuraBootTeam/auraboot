package com.auraboot.framework.meta.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;

/**
 * 绑定关系兼容性检查请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingCompatibilityRequest {

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
     * 检查级别
     */
    private CheckLevel checkLevel;

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
    public BindingCompatibilityRequest() {
        this.checkLevel = CheckLevel.FULL;
        this.includeDetails = true;
    }

    /**
     * 检查级别
     */
    public enum CheckLevel {
        /**
         * 基础检查
         */
        BASIC,

        /**
         * 完整检查
         */
        FULL,

        /**
         * 深度检查
         */
        DEEP
    }
}