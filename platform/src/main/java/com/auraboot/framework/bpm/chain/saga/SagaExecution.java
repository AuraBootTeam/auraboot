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
@TableName(value = "ab_saga_execution", autoResultMap = true)
public class SagaExecution {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("chain_code")
    private String chainCode;

    @TableField("business_key")
    private String businessKey;

    @TableField("status")
    private String status;

    @TableField("current_step")
    private String currentStep;

    @TableField("total_steps")
    private Integer totalSteps;

    @TableField("completed_steps")
    private Integer completedSteps;

    @TableField(value = "payload", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> payload;

    @TableField("error_message")
    private String errorMessage;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("completed_at")
    private Instant completedAt;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_at")
    private Instant updatedAt;
}
