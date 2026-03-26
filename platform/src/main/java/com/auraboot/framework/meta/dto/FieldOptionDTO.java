package com.auraboot.framework.meta.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 字段选项DTO
 * 用于字段选项的数据传输（如枚举值、下拉选项等）
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
public class FieldOptionDTO {

    /**
     * 业务主键
     */
    private String pid;

    /**
     * 所属字段PID
     */
    private String fieldPid;

    /**
     * 选项值
     */
    private String optionValue;

    /**
     * 选项标签（显示文本）
     */
    private String optionLabel;

    /**
     * 选项描述
     */
    private String description;

    /**
     * 选项排序
     */
    private Integer sortOrder;

    /**
     * 是否默认选中
     */
    private Boolean isDefault;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 选项颜色
     */
    private String color;

    /**
     * 选项图标
     */
    private String icon;

    /**
     * 扩展属性（JSON格式）
     */
    private Map<String, Object> extendedProperties;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}