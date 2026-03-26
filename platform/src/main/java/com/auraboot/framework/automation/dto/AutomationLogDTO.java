package com.auraboot.framework.automation.dto;

import com.auraboot.framework.automation.entity.AutomationLog.ActionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AutomationLog Data Transfer Object
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationLogDTO {

    private Long id;
    private String pid;
    private Long tenantId;

    private String automationId;
    private String automationName;

    private String triggerType;
    private String triggerRecordId;
    private Map<String, Object> triggerPayload;

    private String status;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private String errorMessage;

    private List<ActionResult> actionResults;

    private Instant createdAt;
}
