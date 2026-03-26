package com.auraboot.framework.versioning.entity;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Unified design version history entity.
 * Stores version snapshots for all designer types (PAGE, DASHBOARD, BPMN, REPORT).
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@TableName(value = "ab_design_version_history", autoResultMap = true)
public class DesignVersionHistory {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /**
     * Designer type: PAGE, DASHBOARD, BPMN, REPORT
     */
    @TableField("resource_type")
    private String resourceType;

    /**
     * PID of the resource being versioned
     */
    @TableField("resource_id")
    private String resourceId;

    /**
     * Version label (e.g. "1.0.0", "3", or null for auto-increment)
     */
    @TableField("version")
    private String version;

    /**
     * Full JSONB snapshot of the resource state at this point in time
     */
    @TableField(value = "schema_snapshot", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode schemaSnapshot;

    /**
     * Operation type: CREATE, UPDATE, PUBLISH, UNPUBLISH, ARCHIVE, ROLLBACK
     */
    @TableField("operation")
    private String operation;

    /**
     * User PID who performed the operation
     */
    @TableField("operation_by")
    private String operationBy;

    /**
     * When the operation was performed
     */
    @TableField("operation_at")
    private Instant operationAt;

    /**
     * Optional change description
     */
    @TableField("description")
    private String description;

    /**
     * PID of the parent version this was based on
     */
    @TableField("parent_version_id")
    private String parentVersionId;

    /**
     * Extension metadata
     */
    @TableField(value = "metadata", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode metadata;

    @TableField("created_at")
    private Instant createdAt;
}
