package com.auraboot.framework.plugin.marketplace.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_marketplace_solution_install", autoResultMap = true)
public class MarketplaceSolutionInstall {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("solution_pid")
    private String solutionPid;

    @TableField(value = "installed_plugin_pids", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String installedPluginPids;

    @TableField("installed_at")
    private Instant installedAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
