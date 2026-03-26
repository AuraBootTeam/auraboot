package com.auraboot.framework.automation.dto;

import com.auraboot.framework.automation.entity.AutomationLog.ActionResult;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Debug session response DTO
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@Builder
public class DebugSessionDTO {
    private Long id;
    private String pid;
    private String automationId;
    private String recordId;
    private String status;
    private Integer currentActionIndex;
    private Integer totalActions;
    private List<Integer> breakpoints;
    private Map<String, Object> executionContext;
    private List<ActionResult> actionResults;
    private Map<String, Object> triggerPayload;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
