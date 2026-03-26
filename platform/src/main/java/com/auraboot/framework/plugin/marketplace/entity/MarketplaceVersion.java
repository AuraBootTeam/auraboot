package com.auraboot.framework.plugin.marketplace.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_marketplace_version", autoResultMap = true)
public class MarketplaceVersion {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("marketplace_plugin_pid")
    private String marketplacePluginPid;

    @TableField("version")
    private String version;

    @TableField("version_major")
    private Integer versionMajor;

    @TableField("version_minor")
    private Integer versionMinor;

    @TableField("version_patch")
    private Integer versionPatch;

    @TableField("changelog")
    private String changelog;

    @TableField("changelog_zh")
    private String changelogZh;

    @TableField("dependencies")
    private String dependencies;

    @TableField("min_platform_version")
    private String minPlatformVersion;

    @TableField("dsl_version")
    private Integer dslVersion;

    @TableField("manifest_snapshot")
    private String manifestSnapshot;

    @TableField("resource_summary")
    private String resourceSummary;

    @TableField("package_size")
    private Long packageSize;

    @TableField("package_checksum")
    private String packageChecksum;

    @TableField("status")
    private String status;

    @TableField("validation_result")
    private String validationResult;

    @TableField("install_count")
    private Integer installCount;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("tenant_id")
    private Long tenantId;
}
