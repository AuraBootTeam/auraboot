package com.auraboot.framework.engagement.entity;

import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * User engagement record: favorites, recently-visited, and pinned targets.
 * <p>
 * target_type: "menu" | "record" | "page"
 * engagement_type: "favorite" | "recent" | "pinned"
 * </p>
 */
@Data
@TableName(value = "ab_user_engagement", autoResultMap = true)
public class UserEngagement {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long tenantId;

    /**
     * Type of the target: menu, record, page, etc.
     */
    private String targetType;

    /**
     * Unique identifier of the target (menu code, record composite key, page key, etc.).
     */
    private String targetId;

    /**
     * Human-readable label cached from the target at time of engagement.
     */
    private String targetLabel;

    /**
     * Optional JSON context (e.g. model code, icon, route path).
     */
    @TableField(value = "target_context", typeHandler = PluginSettingsTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> targetContext;

    /**
     * Engagement type: favorite, recent, pinned.
     */
    private String engagementType;

    /**
     * Manual sort order for pinned / favorite items.
     */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
