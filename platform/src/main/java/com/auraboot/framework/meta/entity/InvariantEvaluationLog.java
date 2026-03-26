package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Invariant Evaluation Log entity.
 * Records all invariant evaluations (passed and violated) for audit and monitoring.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Data
@TableName("ab_invariant_evaluation_log")
public class InvariantEvaluationLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("invariant_code")
    private String invariantCode;

    @TableField("invariant_type")
    private String invariantType;

    @TableField("scope_type")
    private String scopeType;

    @TableField("scope_ref")
    private String scopeRef;

    @TableField("model_code")
    private String modelCode;

    /**
     * Target record pid (nullable for ALWAYS batch).
     */
    @TableField("record_id")
    private String recordId;

    /**
     * Triggering command code (nullable for ALWAYS).
     */
    @TableField("command_code")
    private String commandCode;

    /**
     * true = passed, false = violated.
     */
    @TableField("evaluation_result")
    private Boolean evaluationResult;

    @TableField("severity")
    private String severity;

    @TableField("expression")
    private String expression;

    @TableField("error_message")
    private String errorMessage;

    /**
     * JSONB - payload/state snapshot at evaluation time.
     */
    @TableField("context_snapshot")
    private String contextSnapshot;

    @TableField("execution_time_ms")
    private Long executionTimeMs;

    @TableField("created_at")
    private Instant createdAt;
}
