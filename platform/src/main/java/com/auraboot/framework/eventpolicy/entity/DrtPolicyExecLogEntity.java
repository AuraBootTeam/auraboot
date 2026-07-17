package com.auraboot.framework.eventpolicy.entity;

import com.auraboot.framework.application.database.mybatis.JsonbMapTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.Map;

/**
 * EventPolicy action execution log row (docs/2.md §8.2). A unique (tenant_id, idempotency_key)
 * gives restart-durable idempotency for the {@code PolicyExecutor}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@TableName(value = "ab_drt_policy_exec_log", autoResultMap = true)
public class DrtPolicyExecLogEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("policy_code")
    private String policyCode;

    @TableField("decision_trace_id")
    private String decisionTraceId;

    @TableField("correlation_id")
    private String correlationId;

    @TableField("rule_code")
    private String ruleCode;

    @TableField("action_type")
    private String actionType;

    @TableField("status")
    private String status;

    @TableField(value = "error_message", updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    @TableField(value = "result_payload", typeHandler = JsonbMapTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> resultPayload;

    @TableField("failure_strategy")
    private String failureStrategy;

    @TableField(value = "action_payload", typeHandler = JsonbMapTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> actionPayload;

    @TableField(value = "context_payload", typeHandler = JsonbMapTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> contextPayload;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("max_attempts")
    private Integer maxAttempts;

    @TableField(value = "next_retry_at", updateStrategy = FieldStrategy.ALWAYS)
    private Instant nextRetryAt;

    @TableField("last_retry_at")
    private Instant lastRetryAt;

    @TableField(value = "dead_lettered_at", updateStrategy = FieldStrategy.ALWAYS)
    private Instant deadLetteredAt;

    @TableField("executed_at")
    private Instant executedAt;
}
