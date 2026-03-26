package com.auraboot.framework.bpm.chain.saga;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_saga_step", autoResultMap = true)
public class SagaStep {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("saga_execution_id")
    private String sagaExecutionId;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("step_order")
    private Integer stepOrder;

    @TableField("node_id")
    private String nodeId;

    @TableField("command_code")
    private String commandCode;

    @TableField("compensation_command")
    private String compensationCommand;

    @TableField("status")
    private String status;

    @TableField(value = "input_params", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> inputParams;

    @TableField(value = "output_data", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> outputData;

    @TableField("record_id")
    private String recordId;

    @TableField("error_message")
    private String errorMessage;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("completed_at")
    private Instant completedAt;
}
