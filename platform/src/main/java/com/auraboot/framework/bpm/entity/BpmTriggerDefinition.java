package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import com.auraboot.framework.common.constant.StatusConstants;
import lombok.AllArgsConstructor;
import com.auraboot.framework.common.constant.StatusConstants;
import lombok.Builder;
import com.auraboot.framework.common.constant.StatusConstants;
import lombok.Data;
import com.auraboot.framework.common.constant.StatusConstants;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * BPM trigger definition entity.
 * Manages process triggers (scheduled, event, webhook, manual).
 * Maps to table: ab_bpm_trigger_definition
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_trigger_definition", autoResultMap = true)
public class BpmTriggerDefinition {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("process_key")
    private String processKey;

    @TableField("trigger_type")
    private String triggerType;

    @TableField(value = "trigger_config", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> triggerConfig;

    @Builder.Default
    @TableField("status")
    private String status = "disabled";

    @TableField("last_fired_at")
    private Instant lastFiredAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @Builder.Default
    @TableLogic
    private Boolean deletedFlag = false;
}
