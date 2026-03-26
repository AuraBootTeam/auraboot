package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 字典项响应DTO
 * 用于字典项数据的返回
 */
@Data
public class DictItemDTO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * PID
     */
    private String pid;

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
     * 字典项值
     */
    private String value;

    /**
     * 字典项标签
     */
    private String label;

    /**
     * 父级值（级联字典使用）
     */
    private String parentValue;

    /**
     * 排序号
     */
    private Integer sortNo;

    /**
     * 状态（ENABLED/DISABLED）
     */
    private String status;

    /**
     * 描述
     */
    private String description;

    /**
     * 扩展属性
     */
    private Map<String, Object> attributes;

    /**
     * 图标
     */
    private String icon;

    /**
     * 颜色
     */
    private String color;

    /**
     * 是否默认值
     */
    private Boolean isDefault;

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

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
     * 版本号
     */
    private Integer version;
}