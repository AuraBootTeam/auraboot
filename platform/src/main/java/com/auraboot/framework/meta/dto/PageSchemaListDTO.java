package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Page Schema List DTO
 * Used for list API responses, excludes large fields like blocks for better performance
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PageSchemaListDTO extends AbstractResponse {

    /**
     * Business primary key
     */
    @JsonProperty("pid")
    private String pid;

    /**
     * Page unique identifier
     */
    @JsonProperty("pageKey")
    private String pageKey;

    /**
     * Associated model code (optional)
     */
    @JsonProperty("modelCode")
    private String modelCode;

    /**
     * Page kind (list, form, detail, dashboard).
     */
    @JsonProperty("kind")
    private String kind;

    /**
     * Page name
     */
    @JsonProperty("name")
    private String name;

    /**
     * Page title
     */
    @JsonProperty("title")
    private String title;

    /**
     * Page description
     */
    @JsonProperty("description")
    private String description;

    /**
     * Meta info (JSON format) - lightweight metadata for list display
     */
    @JsonProperty("metaInfo")
    private Map<String, Object> metaInfo;

    /**
     * Whether it's a template
     */
    @JsonProperty("isTemplate")
    private Boolean isTemplate;

    /**
     * Template category
     */
    @JsonProperty("templateCategory")
    private String templateCategory;

    /**
     * Sort weight
     */
    @JsonProperty("sortWeight")
    private Integer sortWeight;

    /**
     * Published time
     */
    @JsonProperty("publishedAt")
    private LocalDateTime publishedAt;

    /**
     * Tags (JSON format)
     */
    @JsonProperty("tags")
    private Map<String, Object> tags;

    /**
     * Version number
     */
    @JsonProperty("version")
    private Integer version;

    /**
     * Semantic version
     */
    @JsonProperty("semver")
    private String semver;

    /**
     * Row version
     */
    @JsonProperty("rowVersion")
    private Integer rowVersion;

    /**
     * Whether current version
     */
    @JsonProperty("isCurrent")
    private Boolean isCurrent;

    /**
     * Created time
     */
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    /**
     * Updated time
     */
    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}
