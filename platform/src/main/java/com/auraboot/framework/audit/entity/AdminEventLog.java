package com.auraboot.framework.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Cross-cutting administrative-action audit log entry.
 *
 * <p>Domain-agnostic ledger for ops-relevant administrative actions
 * (env lock/unlock, promotion apply, plugin import, admin user disable, …)
 * so compliance / oncall can answer "who did what to which resource and
 * when". Distinct from {@code ab_permission_audit_log} (DENY decisions
 * on the permission hot path) and {@code ab_command_audit_log} (runtime
 * command pipeline events).
 *
 * <p>Writes are fire-and-forget — the {@code AdminEventLogService}
 * swallows exceptions and never propagates to the caller, so audit-write
 * failures cannot break the action being audited.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "ab_admin_event_log", autoResultMap = true)
public class AdminEventLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ULID-style external identifier (auto-filled). */
    private String pid;

    /** Tenant owning this log entry. */
    private Long tenantId;

    /** Acting member (user) — nullable for system / api actors. */
    private Long actorUserId;

    /** {@code user} | {@code system} | {@code api}. */
    @Builder.Default
    private String actorType = "user";

    /** {@code domain.action} format, e.g. {@code environment.lock}. */
    private String actionType;

    /** Domain type of the resource acted on (free text; mirrors {@link #actionType} domain). */
    private String resourceType;

    /** PID of the resource acted on (nullable for non-resource-scoped actions). */
    private String resourcePid;

    /** {@code true} for completed actions, {@code false} for failed attempts. */
    private Boolean success;

    /** Reason text — lock reason, unlock reason, or error message on failure. */
    private String reason;

    /** Optional structured payload (before/after diff, extra context). */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode payload;

    private Instant createdAt;
}
