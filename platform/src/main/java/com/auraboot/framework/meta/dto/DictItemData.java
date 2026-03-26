package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 字典项数据DTO
 * 用于字典数据加载时的统一数据结构
 */
@Data
public class DictItemData {

    /**
     * 字典项值
     */
    private String value;

    /**
     * 字典项标签
     */
    private String label;

    /**
     * 字典项描述
     */
    private String description;

    /**
     * 排序顺序
     */
    private Integer sortOrder;

    /**
     * 父级值（用于级联字典）
     */
    private String parentValue;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 扩展属性
     */
    private Object extension;

    /**
     * 构造函数
     */
    public DictItemData() {
        this.enabled = true;
        this.sortOrder = 0;
    }

    /**
     * 构造函数
     * @param value 值
     * @param label 标签
     */
    public DictItemData(String value, String label) {
        this();
        this.value = value;
        this.label = label;
    }

    /**
     * 构造函数
     * @param value 值
     * @param label 标签
     * @param description 描述
     */
    public DictItemData(String value, String label, String description) {
        this(value, label);
        this.description = description;
    }

    /**
     * 检查是否为根级项（无父级）
     * @return 是否为根级项
     */
    public boolean isRootItem() {
        return parentValue == null || parentValue.trim().isEmpty();
    }

    /**
     * 检查是否为子级项（有父级）
     * @return 是否为子级项
     */
    public boolean isChildItem() {
        return !isRootItem();
    }

    /**
     * 获取显示文本
     * @return 显示文本
     */
    public String getDisplayText() {
        return label != null ? label : value;
    }
}