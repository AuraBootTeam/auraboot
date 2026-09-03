package com.auraboot.framework.dashboard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Dashboard module (folder) entity — tenant-scoped folder tree used to
 * organize dashboards, mirroring the Cordys dashboard-module surfaces.
 *
 * @author AuraBoot Team
 * @since 4.2.0
 */
@Data
@TableName("ab_dashboard_module")
public class DashboardModule {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("name")
    private String name;

    /**
     * Parent folder id; null means the folder sits at the tree root.
     */
    @TableField("parent_id")
    private Long parentId;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("deleted_flag")
    private Boolean deletedFlag;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;
}
