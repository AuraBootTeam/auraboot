package com.auraboot.framework.automation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Per-node runtime status row for a single automation run (G5).
 *
 * <p>One row is inserted per (automation_log, node) when the
 * {@code AutomationActionServiceTaskDelegate} enters a node and updated on exit.
 * Lets the designer overlay a "what actually ran / where it failed" view on top of
 * the flow graph — SmartEngine runs automations in MEMORY (CUSTOM) storage mode and
 * does not otherwise persist execution state.
 *
 * <p>Status vocabulary intentionally matches the SDK {@code nodeStatus} prop:
 * {@code pending | running | completed | failed | skipped}.
 */
@Data
@TableName("ab_automation_node_execution")
public class AutomationNodeExecution {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("automation_log_id")
    private Long automationLogId;

    @TableField("automation_id")
    private String automationId;

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("node_id")
    private String nodeId;

    @TableField("status")
    private String status;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("completed_at")
    private Instant completedAt;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private Instant createdAt;
}
