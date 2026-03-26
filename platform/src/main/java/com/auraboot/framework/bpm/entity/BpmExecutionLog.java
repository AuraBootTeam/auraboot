package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * BPM execution log entity.
 * Records node-level execution events for orchestrated processes.
 * Maps to table: ab_bpm_execution_log
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_execution_log", autoResultMap = true)
public class BpmExecutionLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("execution_id")
    private String executionId;

    @TableField("node_id")
    private String nodeId;

    @TableField("node_type")
    private String nodeType;

    @TableField("event_type")
    private String eventType;

    @TableField(value = "input_data", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> inputData;

    @TableField(value = "output_data", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> outputData;

    @TableField("error_message")
    private String errorMessage;

    @TableField("error_stack")
    private String errorStack;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
