package com.auraboot.framework.automation.dto;

import com.auraboot.framework.automation.entity.AutomationLog.ActionResult;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * SSE event DTO for debug session real-time updates
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@Builder
public class DebugEventDTO {

    /** Event type: ACTION_STARTED, ACTION_COMPLETED, ACTION_FAILED, SESSION_PAUSED, SESSION_COMPLETED, SESSION_STOPPED */
    private String eventType;

    /** Debug session PID */
    private String sessionId;

    /** Current action index */
    private Integer actionIndex;

    /** Action type (e.g. SEND_NOTIFICATION, CONDITION) */
    private String actionType;

    /** Action label */
    private String actionLabel;

    /** Action result (for completed/failed events) */
    private ActionResult actionResult;

    /** Updated execution context snapshot */
    private Map<String, Object> context;

    /** Error message (for failed events) */
    private String errorMessage;

    /** Event timestamp */
    private Instant timestamp;
}
