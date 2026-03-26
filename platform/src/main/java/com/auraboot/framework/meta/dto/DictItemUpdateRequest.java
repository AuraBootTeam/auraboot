package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 字典项更新请求DTO
 * 用于更新字典项的参数封装
 */
@Data
public class DictItemUpdateRequest {

    /**
     * 字典项标签
     */
    @NotBlank(message = "字典项标签不能为空")
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
}