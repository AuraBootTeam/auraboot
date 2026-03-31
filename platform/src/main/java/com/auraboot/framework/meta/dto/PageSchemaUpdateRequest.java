package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 页面Schema更新请求DTO
 * 用于更新现有的页面Schema
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PageSchemaUpdateRequest extends AbstractUpdateRequest {

    /**
     * 页面唯一标识（通常不可更改）
     */
    @Size(min = 2, max = 100, message = "Page key length must be between 2 and 100")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_-]*$", message = "Page key format invalid")
    @JsonProperty("pageKey")
    private String pageKey;

    /**
     * 关联的模型编码
     */
    @Size(max = 100, message = "Model code length must not exceed 100")
    @JsonProperty("modelCode")
    private String modelCode;

    /**
     * 页面名称（显示名称）
     * 可选，长度限制
     */
    @Size(min = 2, max = 100, message = "{page.schema.name.size}")
    @JsonProperty("name")
    private String name;

    /**
     * 页面标题
     * 可选，长度限制
     */
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
     * Page kind (list, form, detail, dashboard).
     */
    @Pattern(regexp = "^(list|form|detail|dashboard|composite)$", message = "Invalid kind")
    @JsonProperty("kind")
    private String kind;

    /**
     * Page profile (sub-type within a kind).
     */
    @JsonProperty("profile")
    private String profile;

    /**
     * Layout configuration (JSON object for page-level layout settings).
     */
    @JsonProperty("layout")
    private Map<String, Object> layout;

    /**
     * Ordered list of page blocks.
     */
    @JsonProperty("blocks")
    private List<Object> blocks;

    /**
     * 元信息（JSON格式）
     * 可选
     */
    @JsonProperty("metaInfo")
    private Map<String, Object> metaInfo;

    /**
     * 是否为模板
     * 可选
     */
    @JsonProperty("isTemplate")
    private Boolean isTemplate;

    /**
     * 模板分类
     * 可选，当isTemplate为true时，此字段有意义
     */
    @Size(max = 50, message = "{page.schema.template.category.size}")
    @JsonProperty("templateCategory")
    private String templateCategory;

    /**
     * 排序权重
     * 可选，数值越小排序越靠前
     */
    @Min(value = 0, message = "{page.schema.sort.weight.min}")
    @Max(value = 9999, message = "{page.schema.sort.weight.max}")
    @JsonProperty("sortWeight")
    private Integer sortWeight;

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
     * Optimistic lock — expected row version.
     * When provided, the update will fail with 409 Conflict if the current
     * row_version in the database does not match this value.
     * Omit to skip optimistic lock check (backward compatible).
     */
    @JsonProperty("rowVersion")
    private Integer rowVersion;

}