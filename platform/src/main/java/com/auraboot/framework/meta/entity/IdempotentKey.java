package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Idempotent Key entity for AOP-based generic idempotency.
 * Stores idempotent keys with status tracking and cached response data.
 *
 * <p>Status lifecycle:
 * <ul>
 *   <li>PROCESSING - Key claimed, method executing</li>
 *   <li>COMPLETED - Method succeeded, response cached</li>
 *   <li>EXPIRED - Method failed or TTL exceeded, key available for retry</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_idempotent_key")
public class IdempotentKey {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("idempotent_key")
    private String idempotentKey;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("command_code")
    private String commandCode;

    @TableField("request_hash")
    private String requestHash;

    @TableField("status")
    private String status;

    @TableField(value = "response_data", typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String responseData;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField("expired_at")
    private Instant expiredAt;

    @TableField("created_by")
    private Long createdBy;

    // Status constants
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_EXPIRED = "expired";
}
