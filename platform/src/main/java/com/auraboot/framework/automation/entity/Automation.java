package com.auraboot.framework.automation.entity;

import com.auraboot.framework.automation.typehandler.ActionsTypeHandler;
import com.auraboot.framework.automation.typehandler.TriggerConfigTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Automation entity - Workflow automation rules
 * Triggered by data changes, state transitions, or schedules
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
@TableName(value = "ab_automation", autoResultMap = true)
public class Automation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /**
     * Automation name
     */
    @TableField("name")
    private String name;

    /**
     * Description
     */
    @TableField("description")
    private String description;

    /**
     * Associated model code
     */
    @TableField("model_code")
    private String modelCode;

    /**
     * Trigger type: ON_RECORD_CREATE, ON_RECORD_UPDATE, ON_FIELD_CHANGE, ON_STATE_CHANGE, SCHEDULED, WEBHOOK
     */
    @TableField("trigger_type")
    private String triggerType;

    /**
     * Trigger configuration (JSONB)
     */
    @TableField(value = "trigger_config", typeHandler = TriggerConfigTypeHandler.class, jdbcType = JdbcType.OTHER)
    private TriggerConfig triggerConfig;

    /**
     * SpEL condition expression for triggering
     */
    @TableField("trigger_condition")
    private String triggerCondition;

    /**
     * List of actions to execute (JSONB)
     */
    @TableField(value = "actions", typeHandler = ActionsTypeHandler.class, jdbcType = JdbcType.OTHER)
    private List<AutomationAction> actions;

    /**
     * React Flow visual designer data (nodes, edges, viewport) stored as JSONB
     */
    @TableField(value = "flow_config", typeHandler = com.auraboot.framework.automation.typehandler.TriggerPayloadTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> flowConfig;

    /**
     * Whether automation is enabled
     */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * Last trigger timestamp
     */
    @TableField("last_triggered_at")
    private Instant lastTriggeredAt;

    /**
     * Total trigger count
     */
    @TableField("trigger_count")
    private Long triggerCount;

    @TableField("deleted_flag")
    @TableLogic(value = "false", delval = "true")
    private Boolean deletedFlag;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;

    // Convenience methods

    /**
     * Null-safe enabled check (avoids overloaded getter ambiguity with Lombok @Data).
     * Lombok generates getEnabled() for Boolean field; this method uses a different name.
     */
    public boolean isActive() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean isRecordCreateTrigger() {
        return "on_record_create".equals(triggerType);
    }

    public boolean isRecordUpdateTrigger() {
        return "on_record_update".equals(triggerType);
    }

    public boolean isFieldChangeTrigger() {
        return "on_field_change".equals(triggerType);
    }

    public boolean isStateChangeTrigger() {
        return "on_state_change".equals(triggerType);
    }

    public boolean isScheduledTrigger() {
        return "scheduled".equals(triggerType);
    }

    public boolean isWebhookTrigger() {
        return "webhook".equals(triggerType);
    }
}
