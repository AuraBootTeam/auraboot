package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系验证请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingValidationRequest {

    /**
     * 模型ID（可选）
     */
    private Long modelId;

    /**
     * 字段ID（可选）
     */
    private Long fieldId;

    /**
     * 验证类型
     */
    private ValidationType validationType;

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
    public BindingValidationRequest() {
        this.validationType = ValidationType.FULL;
        this.includeDetails = true;
    }

    /**
     * 验证类型
     */
    public enum ValidationType {
        BASIC,      // 基础验证
        FULL,       // 完整验证
        INTEGRITY   // 完整性验证
    }
}