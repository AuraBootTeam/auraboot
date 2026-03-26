package com.auraboot.framework.view.entity;

import com.auraboot.framework.view.typehandler.ViewConfigTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * SavedView entity - User customizable view configuration
 * Stores column display, sorting, filtering configurations
 * Supports personal and shared views
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
@TableName(value = "ab_saved_view", autoResultMap = true)
public class SavedView {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /**
     * View name displayed to user
     */
    @TableField("name")
    private String name;

    /**
     * View description
     */
    @TableField("description")
    private String description;

    /**
     * Associated model code (required)
     */
    @TableField("model_code")
    private String modelCode;

    /**
     * Associated page key (optional - null means model-level view)
     */
    @TableField("page_key")
    private String pageKey;

    /**
     * View scope: PERSONAL, TEAM, GLOBAL
     */
    @TableField("scope")
    private String scope;

    /**
     * View type: TABLE, KANBAN, CALENDAR, GALLERY, GANTT, TREE
     */
    @TableField("view_type")
    private String viewType;

    /**
     * Owner user ID (for PERSONAL views)
     */
    @TableField("owner_id")
    private String ownerId;

    /**
     * Team ID (for TEAM views)
     */
    @TableField("team_id")
    private String teamId;

    /**
     * View configuration as JSONB
     * Contains: columns, sorts, filters, groupBy, pagination, etc.
     */
    @TableField(value = "view_config", typeHandler = ViewConfigTypeHandler.class, jdbcType = JdbcType.OTHER)
    private ViewConfig viewConfig;

    /**
     * Whether to allow access to full model fields (beyond page schema)
     */
    @TableField("allow_full_model")
    private Boolean allowFullModel;

    /**
     * Whether this is the default view
     */
    @TableField("is_default")
    private Boolean isDefault;

    /**
     * Whether this is an auto-saved implicit view (not shown in view selector)
     */
    @TableField("is_implicit")
    private Boolean isImplicit;

    /**
     * Sort order for view list display
     */
    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("deleted_flag")
    @TableLogic(value = "false", delval = "true")
    private Boolean deletedFlag;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;

    // Convenience methods

    public boolean isPersonal() {
        return "personal".equals(scope);
    }

    public boolean isTeam() {
        return "team".equals(scope);
    }

    public boolean isGlobal() {
        return "global".equals(scope);
    }

    public boolean isModelLevelView() {
        return pageKey == null || pageKey.isEmpty();
    }
}
