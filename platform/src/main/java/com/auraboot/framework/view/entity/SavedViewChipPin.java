package com.auraboot.framework.view.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * A pin of a SavedView to the list-page quick-filter chip row.
 *
 * Half A (plugin/admin global pins) needs no row — it lives in
 * {@code ab_saved_view.view_config.meta.pinnedAsQuickFilter}. A user or team
 * pinning a shared view must not mutate that view's meta, so their pin is a
 * per-(principal, view) association stored here (table {@code ab_saved_view_chip_pin}).
 */
@Data
@TableName(value = "ab_saved_view_chip_pin")
public class SavedViewChipPin {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /** 'personal' (user_id set) or 'team' (team_id set). */
    @TableField("scope")
    private String scope;

    @TableField("user_id")
    private String userId;

    @TableField("team_id")
    private String teamId;

    /** The pinned SavedView pid (soft reference to ab_saved_view.pid). */
    @TableField("view_pid")
    private String viewPid;

    @TableField("model_code")
    private String modelCode;

    @TableField("page_key")
    private String pageKey;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("created_by")
    private String createdBy;
}
