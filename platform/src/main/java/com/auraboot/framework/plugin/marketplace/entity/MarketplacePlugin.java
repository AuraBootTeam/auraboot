package com.auraboot.framework.plugin.marketplace.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_marketplace_plugin", autoResultMap = true)
public class MarketplacePlugin {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("plugin_id")
    private String pluginId;

    @TableField("namespace")
    private String namespace;

    @TableField("display_name")
    private String displayName;

    @TableField("display_name_zh")
    private String displayNameZh;

    @TableField("display_name_en")
    private String displayNameEn;

    @TableField("summary")
    private String summary;

    @TableField("description")
    private String description;

    @TableField("author")
    private String author;

    @TableField("homepage")
    private String homepage;

    @TableField("icon_url")
    private String iconUrl;

    @TableField("readme_markdown")
    private String readmeMarkdown;

    @TableField("screenshots")
    private String screenshots;

    @TableField("readme_override")
    private String readmeOverride;

    @TableField("screenshots_override")
    private String screenshotsOverride;

    @TableField("average_rating")
    private java.math.BigDecimal averageRating;

    @TableField("review_count")
    private Integer reviewCount;

    @TableField("plugin_type")
    private String pluginType;

    @TableField("category_code")
    private String categoryCode;

    @TableField("tags")
    private String tags;

    @TableField("status")
    private String status;

    @TableField("visibility")
    private String visibility;

    @TableField("featured")
    private Boolean featured;

    @TableField("install_count")
    private Integer installCount;

    @TableField("latest_version")
    private String latestVersion;

    @TableField("total_versions")
    private Integer totalVersions;

    @TableField("publisher_tenant_id")
    private Long publisherTenantId;

    @TableField("publisher_user_id")
    private Long publisherUserId;

    @TableField("review_notes")
    private String reviewNotes;

    @TableField("reviewed_by")
    private Long reviewedBy;

    @TableField("reviewed_at")
    private Instant reviewedAt;

    @TableField("min_platform_version")
    private String minPlatformVersion;

    @TableField("license_mode")
    private String licenseMode;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("deleted_flag")
    @TableLogic(value = "false", delval = "true")
    private Boolean deletedFlag;
}
