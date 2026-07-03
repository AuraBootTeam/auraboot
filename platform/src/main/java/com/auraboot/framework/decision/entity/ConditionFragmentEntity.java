package com.auraboot.framework.decision.entity;

import com.auraboot.framework.decision.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Reusable condition fragment owned by Decision Runtime.
 */
@Data
@TableName(value = "ab_drt_condition_fragment", autoResultMap = true)
public class ConditionFragmentEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("fragment_code")
    private String fragmentCode;

    @TableField("fragment_name")
    private String fragmentName;

    @TableField("description")
    private String description;

    @TableField("scope_type")
    private String scopeType;

    @TableField("scope_ref")
    private String scopeRef;

    @TableField("version")
    private Integer version;

    @TableField("status")
    private String status;

    @TableField(value = "condition_spec_json", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode conditionSpecJson;

    @TableField(value = "field_refs_json", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode fieldRefsJson;

    @TableField(value = "decision_refs_json", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode decisionRefsJson;

    @TableField("owner_module")
    private String ownerModule;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("published_by")
    private String publishedBy;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("updated_at")
    private Instant updatedAt;
}
