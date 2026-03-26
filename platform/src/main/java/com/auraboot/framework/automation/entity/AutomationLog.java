package com.auraboot.framework.automation.entity;

import com.auraboot.framework.automation.typehandler.ActionResultsTypeHandler;
import com.auraboot.framework.automation.typehandler.TriggerPayloadTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Automation execution log entity
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
@TableName(value = "ab_automation_log", autoResultMap = true)
public class AutomationLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /**
     * Reference to automation PID
     */
    @TableField("automation_id")
    private String automationId;

    /**
     * Trigger type that caused this execution
     */
    @TableField("trigger_type")
    private String triggerType;

    /**
     * ID of the record that triggered the automation
     */
    @TableField("trigger_record_id")
    private String triggerRecordId;

    /**
     * Payload data that triggered the automation (JSONB)
     */
    @TableField(value = "trigger_payload", typeHandler = TriggerPayloadTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> triggerPayload;

    /**
     * Execution status: PENDING, RUNNING, SUCCESS, FAILED, SKIPPED
     */
    @TableField("status")
    private String status;

    /**
     * Execution start time
     */
    @TableField("started_at")
    private Instant startedAt;

    /**
     * Execution completion time
     */
    @TableField("completed_at")
    private Instant completedAt;

    /**
     * Error message if failed
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * Results from each action (JSONB)
     */
    @TableField(value = "action_results", typeHandler = ActionResultsTypeHandler.class, jdbcType = JdbcType.OTHER)
    private List<ActionResult> actionResults;

    @TableField("created_at")
    private Instant createdAt;

    // Convenience methods

    public boolean isPending() {
        return StatusConstants.PENDING.equals(status);
    }

    public boolean isRunning() {
        return StatusConstants.RUNNING.equals(status);
    }

    public boolean isSuccess() {
        return StatusConstants.SUCCESS.equals(status);
    }

    public boolean isFailed() {
        return StatusConstants.FAILED.equals(status);
    }

    public boolean isSkipped() {
        return StatusConstants.SKIPPED.equals(status);
    }

    public long getDurationMs() {
        if (startedAt != null && completedAt != null) {
            return completedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
        return 0;
    }

    /**
     * Individual action execution result
     */
    @Data
    public static class ActionResult {
        private Integer sequence;
        private String actionType;
        private String status;
        private Object result;
        private String errorMessage;
        private Long durationMs;
    }
}
