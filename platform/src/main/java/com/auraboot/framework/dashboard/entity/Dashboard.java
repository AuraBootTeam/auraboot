package com.auraboot.framework.dashboard.entity;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Dashboard entity - Configurable dashboard for data visualization
 * Stores widget layout and configuration for interactive dashboards
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@TableName(value = "ab_dashboard", autoResultMap = true)
public class Dashboard {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /**
     * Dashboard unique code within tenant
     */
    @TableField("code")
    private String code;

    /**
     * Dashboard title for display
     */
    @TableField("title")
    private String title;

    /**
     * Dashboard description
     */
    @TableField("description")
    private String description;

    /**
     * Dashboard scope: PERSONAL, TEAM, GLOBAL
     */
    @TableField("scope")
    private String scope;

    /**
     * Owner user PID (for PERSONAL dashboards)
     */
    @TableField("owner_id")
    private String ownerId;

    /**
     * Team ID (for TEAM dashboards)
     */
    @TableField("team_id")
    private String teamId;

    /**
     * Layout configuration as JSONB
     * Structure: { columns: 12, rowHeight: 100, gap: 16, compactType: 'vertical' }
     */
    @TableField(value = "layout_config", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode layoutConfig;

    /**
     * Widgets configuration as JSONB array
     * Structure: [{ id, type, x, y, w, h, config: { title, dataSource, visualization } }]
     */
    @TableField(value = "widgets", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode widgets;

    /**
     * Dashboard status: DRAFT, PUBLISHED
     */
    @TableField("status")
    private String status;

    /**
     * Whether this is the default dashboard
     */
    @TableField("is_default")
    private Boolean isDefault;

    /**
     * Sort order for dashboard list display
     */
    @TableField("sort_order")
    private Integer sortOrder;

    /**
     * Extension data as JSONB
     */
    @TableField(value = "extension", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode extension;

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

    public boolean isDraft() {
        return StatusConstants.DRAFT.equals(status);
    }

    public boolean isPublished() {
        return StatusConstants.PUBLISHED.equals(status);
    }
}
