package com.auraboot.framework.workbench.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Workbench announcement entity.
 *
 * @since 6.5.0
 */
@Data
@TableName("ab_announcement")
public class Announcement {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    @TableField("priority")
    private String priority;

    @TableField("status")
    private String status;

    @TableField("pinned")
    private Boolean pinned;

    @TableField("published_by")
    private Long publishedBy;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("expires_at")
    private Instant expiresAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField("deleted_flag")
    @TableLogic
    private Boolean deletedFlag;
}
