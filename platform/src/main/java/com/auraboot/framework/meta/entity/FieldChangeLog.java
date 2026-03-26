package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * Fine-grained field-level change audit record.
 * Each row represents a single field change on a single record,
 * linking back to the broader audit trail when available.
 *
 * @since 6.2.0
 */
@Data
@TableName(value = "ab_field_change_log", autoResultMap = true)
public class FieldChangeLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("audit_trail_id")
    private Long auditTrailId;

    @TableField("model_code")
    private String modelCode;

    @TableField("record_id")
    private Long recordId;

    @TableField("command_code")
    private String commandCode;

    @TableField("field_code")
    private String fieldCode;

    @TableField("field_label")
    private String fieldLabel;

    @TableField("old_value")
    private String oldValue;

    @TableField("new_value")
    private String newValue;

    @TableField("value_type")
    private String valueType;

    @TableField("change_type")
    private String changeType;

    @TableField("actor_id")
    private Long actorId;

    @TableField("actor_name")
    private String actorName;

    @TableField("changed_at")
    private Instant changedAt;

    @TableField("change_reason")
    private String changeReason;

    @TableField(value = "metadata", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode metadata;
}
