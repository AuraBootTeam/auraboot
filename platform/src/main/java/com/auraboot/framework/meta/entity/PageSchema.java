package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
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
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_page_schema", autoResultMap = true)
public class PageSchema extends AbstractMultiVersionEntity {

    /**
     * 页面唯一标识
     * 格式：{modelCode}_{pageType} 用于 Model 相关页面，如 "device_list"
     * 或自定义 key 用于独立页面，如 "dashboard_main"
     */
    @TableField("page_key")
    private String pageKey;

    /**
     * 关联的模型编码（可选）
     * NULL 表示与模型无关的页面（如仪表盘、设置页）
     */
    @TableField("model_code")
    private String modelCode;

    /**
     * 页面分类
     * MODEL - 模型相关页面
     * DASHBOARD - 仪表盘
     * SETTINGS - 设置页
     * REPORT - 报表
     * TOOL - 工具页
     * CUSTOM - 自定义页面
     */
    @TableField("page_category")
    private String pageCategory;

    /**
     * 页面名称（显示名称）
     */
    @TableField("name")
    private String name;

    /**
     * 页面标题
     */
    @TableField("title")
    private String title;

    /**
     * 页面描述
     */
    @TableField("description")
    private String description;

    /**
     * 页面类型
     */
    @TableField("page_type")
    private String pageType;

    /**
     * DSL Schema定义（JSONB格式）
     */
    @TableField(value = "dsl_schema", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String dslSchema;

    /**
     * DSL schema format version (single integer, incremented on breaking changes).
     * Default: 1 (baseline version for all existing schemas).
     */
    @TableField("schema_version")
    private Integer schemaVersion;

    /**
     * 元信息（JSONB格式）
     */
    @TableField(value = "meta_info", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String metaInfo;

    /**
     * 是否为模板
     */
    @TableField("is_template")
    private Boolean isTemplate;

    /**
     * 模板分类
     */
    @TableField("template_category")
    private String templateCategory;

    /**
     * 排序权重
     */
    @TableField("sort_weight")
    private Integer sortWeight;

    /**
     * 发布时间
     */
    @TableField("published_at")
    private Instant publishedAt;

    /**
     * 标签列表（JSONB格式）
     */
    @TableField(value = "tags", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String tags;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    @TableField("plugin_pid")
    private String pluginPid;
}