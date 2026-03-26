package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Query audit log entity.
 * Maps to the ab_query_audit_log table which records all query executions
 * for auditing, performance monitoring, and anomaly detection.
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_query_audit_log")
public class QueryAuditLog {

    /**
     * Primary key
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Tenant ID
     */
    @TableField("tenant_id")
    private Long tenantId;

    /**
     * Query code (named query identifier, required)
     */
    @TableField("query_code")
    private String queryCode;

    /**
     * User ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * Requester ID (string-based user identifier)
     */
    @TableField("requester_id")
    private String requesterId;

    /**
     * Query ID (execution trace identifier)
     */
    @TableField("query_id")
    private String queryId;

    /**
     * Query name
     */
    @TableField("query_name")
    private String queryName;

    /**
     * Model code
     */
    @TableField("model_code")
    private String modelCode;

    /**
     * Query type
     */
    @TableField("query_type")
    private String queryType;

    /**
     * Query conditions (JSONB, structured conditions)
     */
    @TableField("conditions")
    private String conditions;

    /**
     * Query conditions (text, serialized from SecureQueryRequest)
     */
    @TableField("query_conditions")
    private String queryConditions;

    /**
     * Whether the query was rejected by policy
     */
    @TableField("rejected")
    private Boolean rejected;

    /**
     * Reason for rejection
     */
    @TableField("reject_reason")
    private String rejectReason;

    /**
     * 选择字段
     */
    @TableField("select_fields")
    private String selectFields;

    /**
     * 排序字段
     */
    @TableField("sort_fields")
    private String sortFields;

    /**
     * 分页信息
     */
    @TableField("pagination_info")
    private String paginationInfo;

    /**
     * Execution time in milliseconds (from SecureQueryExecutor)
     */
    @TableField("execution_time_ms")
    private Integer executionTimeMs;

    /**
     * Cost in milliseconds (from NamedQuery executor, legacy column)
     */
    @TableField("cost_ms")
    private Integer costMs;

    /**
     * Result count (number of rows returned)
     */
    @TableField("result_count")
    private Integer resultCount;

    /**
     * Whether the query succeeded
     */
    @TableField("success")
    private Boolean success;

    /**
     * Error message (on failure)
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * Error type (exception class name)
     */
    @TableField("error_type")
    private String errorType;

    /**
     * Client IP address
     */
    @TableField("ip_address")
    private String ipAddress;

    /**
     * HTTP User-Agent header
     */
    @TableField("user_agent")
    private String userAgent;

    /**
     * HTTP request ID (X-Request-ID)
     */
    @TableField("request_id")
    private String requestId;

    /**
     * HTTP session ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * Query complexity score (heuristic)
     */
    @TableField("query_complexity_score")
    private Integer queryComplexityScore;

    /**
     * Whether a cache hit occurred
     */
    @TableField("cache_hit")
    private Boolean cacheHit;

    /**
     * Whether data masking was applied
     */
    @TableField("data_masking_applied")
    private Boolean dataMaskingApplied;

    /**
     * Time spent on permission check (ms)
     */
    @TableField("permission_check_time_ms")
    private Integer permissionCheckTimeMs;

    /**
     * Time spent on security validation (ms)
     */
    @TableField("security_validation_time_ms")
    private Integer securityValidationTimeMs;

    /**
     * Record creation timestamp
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}