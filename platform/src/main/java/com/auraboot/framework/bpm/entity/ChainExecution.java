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
 * Persistent state for command chains with UserTask (approval) nodes.
 * Maps to table: ab_chain_execution
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_chain_execution", autoResultMap = true)
public class ChainExecution {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("process_key")
    private String processKey;

    @TableField("business_key")
    private String businessKey;

    @TableField("chain_mode")
    private String chainMode;

    @TableField("status")
    private String status;

    @TableField("current_node_id")
    private String currentNodeId;

    @TableField(value = "process_variables", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> processVariables;

    @TableField(value = "step_results", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> stepResults;

    @TableField(value = "chain_definition", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> chainDefinition;

    @TableField("error_message")
    private String errorMessage;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("completed_at")
    private Instant completedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_by")
    private Long updatedBy;
}
