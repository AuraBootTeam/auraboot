package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Filter Preset entity.
 * Stores saved filter conditions for list pages, supporting both
 * global (user_id = null) and per-user presets.
 *
 * @since 3.4.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_filter_preset")
public class FilterPreset {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    /** NULL = global preset, non-NULL = personal preset. */
    @TableField("user_id")
    private Long userId;

    @TableField("page_code")
    private String pageCode;

    @TableField("model_code")
    private String modelCode;

    @TableField("name")
    private String name;

    /** JSONB: array of FilterCondition objects. */
    @TableField(value = "conditions", typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String conditions;

    /** Logic operator: AND / OR. */
    @TableField("logic")
    private String logic;

    @TableField("is_default")
    private Boolean isDefault;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
