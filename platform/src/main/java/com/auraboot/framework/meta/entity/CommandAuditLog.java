package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Command Audit Log entity
 * Records command execution history for auditing.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_command_audit_log")
public class CommandAuditLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("command_code")
    private String commandCode;

    @TableField("command_pid")
    private String commandPid;

    @TableField("user_id")
    private Long userId;

    @TableField(value = "request_payload", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String requestPayload;

    @TableField(value = "execution_result", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String executionResult;

    @TableField("success")
    private Boolean success;

    @TableField("error_message")
    private String errorMessage;

    @TableField("execution_time_ms")
    private Long executionTimeMs;

    @TableField("phase_reached")
    private String phaseReached;

    @TableField(value = "phase_timings", jdbcType = org.apache.ibatis.type.JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String phaseTimings;

    @TableField("ip_address")
    private String ipAddress;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
