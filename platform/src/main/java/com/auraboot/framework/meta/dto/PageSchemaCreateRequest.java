package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 页面Schema创建请求DTO
 * 用于创建新的页面Schema
 */
@Data
public class PageSchemaCreateRequest {

    /**
     * 页面唯一标识
     * 必填，格式：{modelCode}_{pageType} 或自定义 key
     * 例如：device_list, dashboard_main
     */
    @NotBlank(message = "Page key is required")
    @Size(min = 2, max = 100, message = "Page key length must be between 2 and 100")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_-]*$", message = "Page key must start with letter and contain only letters, numbers, underscores, and hyphens")
    @JsonProperty("pageKey")
    private String pageKey;

    /**
     * 关联的模型编码（可选）
     * NULL 表示与模型无关的页面
     */
    @Size(max = 100, message = "Model code length must not exceed 100")
    @JsonProperty("modelCode")
    private String modelCode;

    /**
     * 页面分类
     * MODEL, DASHBOARD, SETTINGS, REPORT, TOOL, CUSTOM
     */
    @Pattern(regexp = "^(?i)(model|dashboard|settings|report|tool|custom)$", message = "Invalid page category")
    @JsonProperty("pageCategory")
    private String pageCategory = "model";

    /**
     * 页面名称（显示名称）
     * 必填，长度限制
     */
    @NotBlank(message = "{page.schema.name.not.blank}")
    @Size(min = 2, max = 100, message = "{page.schema.name.size}")
    @JsonProperty("name")
    private String name;

    /**
     * 页面标题
     * 必填，长度限制
     */
    @NotBlank(message = "{page.schema.title.not.blank}")
    @Size(min = 1, max = 200, message = "{page.schema.title.size}")
    @JsonProperty("title")
    private String title;

    /**
     * 页面描述
     * 可选，长度限制
     */
    @Size(max = 1000, message = "{page.schema.description.size}")
    @JsonProperty("description")
    private String description;

    /**
     * 页面类型
     * 必填，枚举值验证
     */
    @NotBlank(message = "{page.schema.page.type.not.blank}")
    @Pattern(regexp = "^(?i)(form|list|detail|dashboard|custom)$", message = "{page.schema.page.type.pattern}")
    @JsonProperty("pageType")
    private String pageType;

    /**
     * DSL Schema定义（JSON格式）
     * 必填，JSON格式验证
     */
    @NotNull(message = "{page.schema.dsl.schema.not.null}")
    @JsonProperty("dslSchema")
    private Map<String, Object> dslSchema;

    /**
     * 元信息（JSON格式）
     * 可选
     */
    @JsonProperty("metaInfo")
    private Map<String, Object> metaInfo;

    /**
     * 是否为模板
     * 默认为false
     */
    @JsonProperty("isTemplate")
    private Boolean isTemplate = false;

    /**
     * 模板分类
     * 当isTemplate为true时，此字段有意义
     */
    @Size(max = 50, message = "{page.schema.template.category.size}")
    @JsonProperty("templateCategory")
    private String templateCategory;

    /**
     * 排序权重
     * 默认为0，数值越小排序越靠前
     */
    @Min(value = 0, message = "{page.schema.sort.weight.min}")
    @Max(value = 9999, message = "{page.schema.sort.weight.max}")
    @JsonProperty("sortWeight")
    private Integer sortWeight = 0;

    /**
     * 标签列表（JSON格式）
     * 可选
     */
    @JsonProperty("tags")
    private Map<String, Object> tags;

    /**
     * 语义化版本号
     * 可选，遵循SemVer规范
     */
    @Pattern(regexp = "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$", 
             message = "{page.schema.semver.pattern}")
    @JsonProperty("semver")
    private String semver;

    /**
     * 扩展信息（JSON格式）
     * 可选，用于存储额外的业务信息
     */
    @JsonProperty("extension")
    private Map<String, Object> extension;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    @JsonProperty("pluginPid")
    private String pluginPid;
}