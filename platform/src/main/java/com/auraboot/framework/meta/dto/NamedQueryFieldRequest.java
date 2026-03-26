package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 命名查询字段请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryFieldRequest {

    /**
     * 字段编码.
     * Accepts both "fieldCode" (API format) and "code" (plugin JSON format).
     */
    @NotBlank(message = "字段编码不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "字段编码必须以字母开头，只能包含字母、数字和下划线")
    @Size(max = 100, message = "字段编码长度不能超过100个字符")
    @JsonAlias("code")
    private String fieldCode;

    /**
     * 列表达式
     */
    @NotBlank(message = "列表达式不能为空")
    @Size(max = 500, message = "列表达式长度不能超过500个字符")
    private String columnExpr;

    /**
     * 数据类型
     */
    @NotBlank(message = "数据类型不能为空")
    @Pattern(regexp = "^(?i)(string|number|date|boolean|json|array)$", message = "数据类型必须是string、number、date、boolean、json或array")
    private String dataType;

    /**
     * 允许的操作符白名单
     */
    private List<String> operators;

    /**
     * 关联的字典编码
     */
    @Size(max = 100, message = "字典编码长度不能超过100个字符")
    private String dictCode;

    /**
     * 是否可排序
     */
    private Boolean sortable = false;

    /**
     * 是否可搜索
     */
    private Boolean searchable = true;

    /**
     * 字段显示名称
     */
    @Size(max = 200, message = "字段显示名称长度不能超过200个字符")
    private String displayName;

    /**
     * 字段描述
     */
    @Size(max = 500, message = "字段描述长度不能超过500个字符")
    private String description;

    /**
     * 字段顺序
     */
    private Integer sortOrder;

    /**
     * 是否必填
     */
    private Boolean required = false;

    /**
     * 字段分组
     */
    @Size(max = 100, message = "字段分组长度不能超过100个字符")
    private String fieldGroup;

    /**
     * UI component type: text, number, numberRange, select, dateRange, date, userPicker, cascader, search, switch
     */
    @Size(max = 50, message = "UI component type max 50 chars")
    private String uiComponent;

    /**
     * Placeholder text for the input
     */
    @Size(max = 200, message = "Placeholder max 200 chars")
    private String placeholder;

    /**
     * Default value for the field
     */
    @Size(max = 500, message = "Default value max 500 chars")
    private String defaultValue;

    /**
     * Linked field code for cascading
     */
    @Size(max = 100, message = "Linked field max 100 chars")
    private String linkedField;

    /**
     * Extra UI configuration (JSON)
     */
    private JsonNode uiConfig;

    /**
     * 字段标签
     */
    private List<String> tags;

    /**
     * 验证规则
     */
    @Size(max = 1000, message = "验证规则长度不能超过1000个字符")
    private String validationRules;

    /**
     * 格式化规则
     */
    @Size(max = 500, message = "格式化规则长度不能超过500个字符")
    private String formatRules;

    /**
     * 字段权重
     */
    private Integer weight = 0;

    /**
     * 是否敏感字段
     */
    private Boolean sensitive = false;

    /**
     * 脱敏规则
     */
    @Size(max = 200, message = "脱敏规则长度不能超过200个字符")
    private String maskingRule;

    /**
     * 是否使用默认操作符
     */
    private Boolean useDefaultOperators = true;

    /**
     * 是否验证操作符
     */
    private Boolean validateOperators = true;
}