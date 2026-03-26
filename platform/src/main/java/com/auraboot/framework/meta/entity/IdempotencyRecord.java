package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Idempotency Record entity
 * Tracks command execution results for idempotent replay.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_idempotency_record")
public class IdempotencyRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("client_request_id")
    private String clientRequestId;

    @TableField("request_hash")
    private String requestHash;

    @TableField("command_code")
    private String commandCode;

    @TableField(value = "outcome", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String outcome;

    @TableField("status")
    private String status;

    @TableField("expires_at")
    private Instant expiresAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
