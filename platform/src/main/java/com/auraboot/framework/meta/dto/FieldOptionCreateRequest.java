package com.auraboot.framework.meta.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * 字段选项创建请求DTO
 * 用于创建字段选项的请求参数（如枚举值、下拉选项等）
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
public class FieldOptionCreateRequest {

    /**
     * 选项值
     */
    @NotBlank(message = "选项值不能为空")
    @Size(max = 100, message = "选项值长度不能超过100个字符")
    private String optionValue;

    /**
     * 选项标签（显示文本）
     */
    @NotBlank(message = "选项标签不能为空")
    @Size(max = 200, message = "选项标签长度不能超过200个字符")
    private String optionLabel;

    /**
     * 选项描述
     */
    @Size(max = 500, message = "选项描述长度不能超过500个字符")
    private String description;

    /**
     * 选项排序
     */
    @NotNull(message = "选项排序不能为空")
    @Min(value = 0, message = "选项排序不能为负数")
    private Integer sortOrder = 0;

    /**
     * 是否默认选中
     */
    @NotNull(message = "是否默认选中不能为空")
    private Boolean isDefault = false;

    /**
     * 是否启用
     */
    @NotNull(message = "是否启用不能为空")
    private Boolean enabled = true;

    /**
     * 选项颜色
     */
    @Size(max = 20, message = "选项颜色长度不能超过20个字符")
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "颜色格式不正确，应为#RRGGBB格式")
    private String color;

    /**
     * 选项图标
     */
    @Size(max = 100, message = "选项图标长度不能超过100个字符")
    private String icon;

    /**
     * 扩展属性（JSON格式）
     */
    private Map<String, Object> extendedProperties;
}