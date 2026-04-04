package com.auraboot.framework.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Permission Audit Log entity.
 *
 * <p>Records every DENY decision from the permission evaluation pipeline
 * for compliance auditing. Only denials are logged to avoid hot-path spam.
 */
@Data
@NoArgsConstructor
@TableName(value = "ab_permission_audit_log", autoResultMap = true)
public class PermissionAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Tenant owning this log entry. */
    private Long tenantId;

    /** Member (user) whose permission was evaluated. */
    private Long memberId;

    /** Resource code (e.g. model code). */
    private String resourceCode;

    /** Action that was evaluated (e.g. "view", "edit", "delete"). */
    private String actionCode;

    /** Target record ID, nullable for non-record operations. */
    private Long recordId;

    /** Final grant/deny result (true = allowed, false = denied). */
    private Boolean result;

    /** Human-readable reason for the decision. */
    private String reason;

    /** Full evaluation trace serialized as JSONB. */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> evaluationTrace;

    private Instant createdAt;
}
