package com.auraboot.framework.meta.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 字段定义响应DTO
 * 用于字段定义数据的返回
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PUBLIC)
public class MetaFieldDTO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * PID
     */
    private String pid;

    /**
     * 字段键
     */
    private String code;

    /**
     * 数据类型
     */
    private String dataType;

    /**
     * 数据源ID
     */
    private Long dataSourceId;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 是否为当前版本
     */
    private Boolean isCurrent;

    /**
     * 状态
     */
    private String status;

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 字段特性配置
     */
    private Map<String, Object> feature;

    /**
     * 引用目标配置
     */
    private Map<String, Object> refTarget;

    /**
     * 索引提示配置
     */
    private Map<String, Object> indexHint;

    /**
     * UI配置
     */
    private Map<String, Object> uiSchema;

    /**
     * 查询配置
     */
    private Map<String, Object> querySchema;

    /**
     * 规则配置
     */
    private Map<String, Object> ruleSchema;

    /**
     * 扩展属性
     */
    private Map<String, Object> extension;

    /**
     * 在模型中的排序
     */
    private Integer fieldOrder;

    /**
     * 是否必填（绑定配置）
     */
    private Boolean required;

    /**
     * 是否可见（绑定配置）
     */
    private Boolean visible;

    /**
     * 是否可编辑（绑定配置）
     */
    private Boolean editable;

    /**
     * 绑定的字典编码
     */
    private String dictCode;

    /**
     * 创建者
     */
    private String createdBy;

    /**
     * 更新者
     */
    private String updatedBy;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * Response remark or hint message
     */
    private String remark;

    /**
     * 获取显示名称
     * @return 显示名称
     */
    public String getDisplayName() {
        if (extension != null) {
            Object name = extension.get("displayName");
            return name != null ? name.toString() : code;
        }
        return code;
    }

    /**
     * 获取描述信息
     * @return 描述信息
     */
    public String getDescription() {
        if (extension != null) {
            Object desc = extension.get("description");
            return desc != null ? desc.toString() : null;
        }
        return null;
    }

    /**
     * 是否必填字段
     * @return 是否必填
     */
    public boolean isRequired() {
        if (feature != null) {
            Object required = feature.get("required");
            return Boolean.TRUE.equals(required);
        }
        return false;
    }

    /**
     * 是否唯一字段
     * @return 是否唯一
     */
    public boolean isUnique() {
        if (feature != null) {
            Object unique = feature.get("unique");
            return Boolean.TRUE.equals(unique);
        }
        return false;
    }
}