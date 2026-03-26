package com.auraboot.framework.automation.entity;

import com.auraboot.framework.automation.typehandler.ActionResultsTypeHandler;
import com.auraboot.framework.automation.typehandler.BreakpointsTypeHandler;
import com.auraboot.framework.automation.typehandler.TriggerPayloadTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Debug session entity for step-through automation debugging.
 * Uses a state-machine approach: step() executes one action at a time.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@TableName(value = "ab_automation_debug_session", autoResultMap = true)
public class DebugSession {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /** Reference to automation PID */
    @TableField("automation_id")
    private String automationId;

    /** Optional record ID for context */
    @TableField("record_id")
    private String recordId;

    /** Session status: PAUSED, RUNNING, COMPLETED, FAILED, STOPPED */
    @TableField("status")
    private String status;

    /** Index of the next action to execute (0-based) */
    @TableField("current_action_index")
    private Integer currentActionIndex;

    /** Action indices where execution should pause */
    @TableField(value = "breakpoints", typeHandler = BreakpointsTypeHandler.class, jdbcType = JdbcType.OTHER)
    private List<Integer> breakpoints;

    /** Current execution context (variables, previous results) */
    @TableField(value = "execution_context", typeHandler = TriggerPayloadTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> executionContext;

    /** Results from executed actions so far */
    @TableField(value = "action_results", typeHandler = ActionResultsTypeHandler.class, jdbcType = JdbcType.OTHER)
    private List<AutomationLog.ActionResult> actionResults;

    /** Trigger payload for the debug run */
    @TableField(value = "trigger_payload", typeHandler = TriggerPayloadTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> triggerPayload;

    /** Error message if failed */
    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private String createdBy;

    // Convenience methods

    public boolean isPaused() {
        return "paused".equals(status);
    }

    public boolean isRunning() {
        return StatusConstants.RUNNING.equals(status);
    }

    public boolean isCompleted() {
        return StatusConstants.COMPLETED.equals(status);
    }

    public boolean isFailed() {
        return StatusConstants.FAILED.equals(status);
    }

    public boolean isStopped() {
        return "stopped".equals(status);
    }

    public boolean isActive() {
        return isPaused() || isRunning();
    }
}
