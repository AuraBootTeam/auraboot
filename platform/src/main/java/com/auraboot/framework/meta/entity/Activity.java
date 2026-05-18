package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Unified activity record for any model.
 * Supports both system-generated (from command execution) and user-created activities.
 */
@Data
@TableName(value = "ab_activity", autoResultMap = true)
public class Activity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /** Target model code (e.g. "sl_sales_order") */
    @TableField("object_model")
    private String objectModel;

    /** Target record PID */
    @TableField("object_record")
    private String objectRecord;

    /** Activity type: STATE_CHANGE, CREATE, UPDATE, DELETE, NOTE, CALL, EMAIL, MEETING, SYSTEM */
    @TableField("activity_type")
    private String activityType;

    @TableField("subject")
    private String subject;

    @TableField("content")
    private String content;

    /** USER, SYSTEM, AGENT */
    @TableField("actor_type")
    private String actorType;

    @TableField("actor_id")
    private Long actorId;

    @TableField("actor_name")
    private String actorName;

    /** Command code that triggered this activity (system-generated only) */
    @TableField("command_code")
    private String commandCode;

    /** CREATE, UPDATE, DELETE, STATE_TRANSITION */
    @TableField("operation_type")
    private String operationType;

    @TableField(value = "metadata", jdbcType = JdbcType.OTHER, typeHandler = JsonbStringTypeHandler.class)
    private String metadata;

    @TableField("occurred_at")
    private Instant occurredAt;

    @TableField("created_at")
    private Instant createdAt;
}
