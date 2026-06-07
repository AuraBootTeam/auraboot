package com.auraboot.framework.eventpolicy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

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

    @TableField("rule_code")
    private String ruleCode;

    @TableField("action_type")
    private String actionType;

    @TableField("status")
    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField("executed_at")
    private Instant executedAt;
}
