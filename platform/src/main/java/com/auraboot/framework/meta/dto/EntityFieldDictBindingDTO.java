package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 实体字段字典绑定DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityFieldDictBindingDTO {

    /**
     * 绑定ID
     */
    private Long id;

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 字段ID
     */
    private Long fieldId;

    /**
     * 字段PID
     */
    private String fieldPid;

    /**
     * 字段键
     */
    private String code;

    /**
     * 字段显示名称
     */
    private String fieldDisplayName;

    /**
     * 字典ID
     */
    private Long dictId;

    /**
     * 字典PID
     */
    private String dictPid;

    /**
     * 字典编码
     */
    private String dictCode;

    /**
     * 字典显示名称
     */
    private String dictDisplayName;

    /**
     * 是否为主字典
     */
    private Boolean isPrimary;

    /**
     * 绑定类型
     */
    private String bindingType;

    /**
     * 绑定配置
     */
    private String bindingConfig;

    /**
     * 绑定状态
     */
    private String bindingStatus;

    /**
     * 创建时间
     */
    private Long createdAt;

    /**
     * 更新时间
     */
    private Long updatedAt;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 更新人
     */
    private String updatedBy;

    /**
     * 检查绑定是否有效
     * @return 是否有效
     */
    public boolean isValid() {
        return fieldId != null && dictId != null && tenantId != null;
    }

    /**
     * 检查是否为主字典绑定
     * @return 是否为主字典
     */
    public boolean isPrimaryBinding() {
        return Boolean.TRUE.equals(isPrimary);
    }

    /**
     * 检查绑定是否激活
     * @return 是否激活
     */
    public boolean isActive() {
        return "active".equalsIgnoreCase(bindingStatus);
    }
}