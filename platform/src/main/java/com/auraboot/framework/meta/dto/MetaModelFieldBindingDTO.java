package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 模型字段绑定关系DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
@Builder
public class MetaModelFieldBindingDTO {

    /**
     * 绑定关系ID
     */
    private Long id;

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 模型ID
     */
    private Long modelId;

    /**
     * 模型编码
     */
    private String modelCode;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 字段ID
     */
    private Long fieldId;

    /**
     * 字段键
     */
    private String code;

    /**
     * 字段名称
     */
    private String fieldName;

    /**
     * 字段类型
     */
    private String fieldType;

    /**
     * 字段排序
     */
    private Integer fieldOrder;

    /**
     * 是否必填
     */
    private Boolean required;

    /**
     * 是否只读
     */
    private Boolean readonly;

    /**
     * 是否可见
     */
    private Boolean visible;

    /**
     * 绑定状态
     */
    private String bindingStatus;

    /**
     * 版本兼容性状态
     */
    private String compatibilityStatus;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 检查绑定关系是否有效
     */
    public boolean isValid() {
        return modelId != null && fieldId != null && fieldOrder != null;
    }

    /**
     * 检查是否为必填字段
     */
    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

    /**
     * 检查是否为只读字段
     */
    public boolean isReadonly() {
        return Boolean.TRUE.equals(readonly);
    }

    /**
     * 检查是否可见
     */
    public boolean isVisible() {
        return !Boolean.FALSE.equals(visible);
    }

    /**
     * 检查版本兼容性是否正常
     */
    public boolean isCompatible() {
        return !"incompatible".equals(compatibilityStatus);
    }
}