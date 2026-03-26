package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 页面Schema数据传输对象
 * 用于对外接口的数据传输
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PageSchemaDTO extends AbstractResponse {

    /**
     * 业务主键
     */
    @JsonProperty("pid")
    private String pid;

    /**
     * 页面唯一标识
     */
    @JsonProperty("pageKey")
    private String pageKey;

    /**
     * 关联的模型编码（可选）
     */
    @JsonProperty("modelCode")
    private String modelCode;

    /**
     * Model category (DOCUMENT, MASTER, TRANSACTION, etc.)
     * Enriched at query time from the associated model.
     */
    @JsonProperty("modelCategory")
    private String modelCategory;

    /**
     * 页面分类
     */
    @JsonProperty("pageCategory")
    private String pageCategory;

    /**
     * 页面名称
     */
    @JsonProperty("name")
    private String name;

    /**
     * 页面标题
     */
    @JsonProperty("title")
    private String title;

    /**
     * 页面描述
     */
    @JsonProperty("description")
    private String description;

    /**
     * 页面类型
     */
    @JsonProperty("pageType")
    private String pageType;

    /**
     * DSL Schema定义（JSON格式）
     */
    @JsonProperty("dslSchema")
    private Map<String, Object> dslSchema;

    /**
     * DSL schema format version (single integer).
     */
    @JsonProperty("schemaVersion")
    private Integer schemaVersion;

    /**
     * 元信息（JSON格式）
     */
    @JsonProperty("metaInfo")
    private Map<String, Object> metaInfo;

    /**
     * 是否为模板
     */
    @JsonProperty("isTemplate")
    private Boolean isTemplate;

    /**
     * 模板分类
     */
    @JsonProperty("templateCategory")
    private String templateCategory;

    /**
     * 排序权重
     */
    @JsonProperty("sortWeight")
    private Integer sortWeight;

    /**
     * 发布时间
     */
    @JsonProperty("publishedAt")
    private LocalDateTime publishedAt;

    /**
     * 标签列表（JSON格式）
     */
    @JsonProperty("tags")
    private Map<String, Object> tags;

    /**
     * 版本号
     */
    @JsonProperty("version")
    private Integer version;

    /**
     * 语义化版本号
     */
    @JsonProperty("semver")
    private String semver;

    /**
     * 行版本号
     */
    @JsonProperty("rowVersion")
    private Integer rowVersion;

    /**
     * 是否为当前版本
     */
    @JsonProperty("isCurrent")
    private Boolean isCurrent;



    /**
     * 扩展信息（JSON格式）
     */
    @JsonProperty("extension")
    private Map<String, Object> extension;

    // 状态字段已在父类 AbstractResponse 中定义，无需重复声明

    /**
     * 创建时间
     */
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}