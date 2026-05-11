package com.auraboot.framework.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Record Share entity — stores per-record sharing grants (ReBAC).
 *
 * <p>A record share allows a specific subject (user, role, or department)
 * to access a specific record regardless of data scope restrictions.
 */
@Data
@TableName("ab_record_share")
public class RecordShare {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;

    private Long tenantId;

    /** Model/resource code (e.g. "crm_opportunity") */
    private String resourceCode;

    /** The numeric ID of the shared record (legacy internal ID). */
    private Long recordId;

    /** Stable public record PID. */
    private String recordPid;

    /** Subject type: "member", "role", "dept" */
    private String subjectType;

    /** Subject ID (legacy internal member, role, or department ID). */
    private Long subjectId;

    /** Stable public subject PID. */
    private String subjectPid;

    /** Optional permission mask (e.g. "read", "read,update") */
    private String permissionMask;

    /** Optional expiration time */
    private Instant expiresAt;

    private Instant createdAt;

    private Long createdBy;
}
