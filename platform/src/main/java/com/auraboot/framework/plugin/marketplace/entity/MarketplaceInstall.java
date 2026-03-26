package com.auraboot.framework.plugin.marketplace.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_marketplace_install", autoResultMap = true)
public class MarketplaceInstall {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("marketplace_plugin_pid")
    private String marketplacePluginPid;

    @TableField("marketplace_version_pid")
    private String marketplaceVersionPid;

    @TableField("plugin_pid")
    private String pluginPid;

    @TableField("installed_version")
    private String installedVersion;

    @TableField("installed_at")
    private Instant installedAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("last_notified_version")
    private String lastNotifiedVersion;
}
