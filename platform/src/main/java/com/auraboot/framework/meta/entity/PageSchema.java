package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.auraboot.framework.environment.annotation.EnvScoped;
import com.auraboot.framework.meta.entity.common.AbstractMultiVersionEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * 页面Schema实体类
 * 对应表：ab_page_schema
 *
 * env-layering PoC: marked {@link EnvScoped} so the persistence layer applies env_id filter
 * (read) and stamp (write) on top of tenant_id.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_page_schema", autoResultMap = true)
@EnvScoped
public class PageSchema extends AbstractMultiVersionEntity {

    @TableField("page_key")
    private String pageKey;

    @TableField("model_code")
    private String modelCode;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    /** Page kind: list, form, detail, dashboard */
    @TableField("kind")
    private String kind;

    /** Schema format version. Always 2 for V2 flat format. */
    @TableField("schema_version")
    private Integer schemaVersion;

    /** Render profile: admin, report, portal, etc. */
    @TableField("profile")
    private String profile;

    /** Localized page title as JSONB: {"zh-CN": "...", "en": "..."} */
    @TableField(value = "title", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String title;

    /** Layout config as JSONB */
    @TableField(value = "layout", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String layout;

    /** Content blocks as JSONB array */
    @TableField(value = "blocks", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String blocks;

    @TableField(value = "meta_info", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String metaInfo;

    @TableField("is_template")
    private Boolean isTemplate;

    @TableField("template_category")
    private String templateCategory;

    @TableField("sort_weight")
    private Integer sortWeight;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField(value = "tags", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String tags;

    @TableField("plugin_pid")
    private String pluginPid;

    /**
     * env-layering PoC: foreign key to ab_environment(id). Auto-filled on insert by
     * {@code EnvIdMetaObjectHandler} (batch 2) from {@link com.auraboot.framework.application.tenant.MetaContext}.
     * Nullable in batch 1; tightened to NOT NULL after auto-fill is wired.
     */
    @TableField("env_id")
    private Long envId;
}