package com.auraboot.framework.plugin.marketplace.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_marketplace_category", autoResultMap = true)
public class MarketplaceCategory {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("code")
    private String code;

    @TableField("display_name_zh")
    private String displayNameZh;

    @TableField("display_name_en")
    private String displayNameEn;

    @TableField("description")
    private String description;

    @TableField("icon")
    private String icon;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("parent_code")
    private String parentCode;

    @TableField("plugin_count")
    private Integer pluginCount;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("tenant_id")
    private Long tenantId;
}
