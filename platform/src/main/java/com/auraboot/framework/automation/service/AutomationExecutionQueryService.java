package com.auraboot.framework.automation.service;

import com.auraboot.framework.automation.dto.AutomationNodeExecutionDTO;
import com.auraboot.framework.automation.entity.AutomationNodeExecution;

import java.util.List;

/**
 * Read-only query service for automation runtime status (G5).
 *
 * <p>Powers the designer's runtime overlay — given an {@code ab_automation_log.id},
 * returns the per-node execution rows that the
 * {@link com.auraboot.framework.automation.bpm.AutomationActionServiceTaskDelegate}
 * wrote during the run. Tenant filtering is mandatory and resolved from
 * {@link com.auraboot.framework.application.tenant.MetaContext}.
 */
public interface AutomationExecutionQueryService {

    /**
     * Get all node-status rows for an automation log id, scoped by the tenant in
     * the current {@link com.auraboot.framework.application.tenant.MetaContext}.
     */
    List<AutomationNodeExecutionDTO> getNodeStatusesByLogId(Long automationLogId);

    /**
     * Get all node-status rows for a SmartEngine process instance id, scoped by
     * the tenant in the current {@link com.auraboot.framework.application.tenant.MetaContext}.
     */
    List<AutomationNodeExecutionDTO> getNodeStatusesByProcessInstanceId(String processInstanceId);

    /**
     * Convert a raw entity row into the wire DTO. Exposed for tests / callers that
     * already hold the entity.
     */
    AutomationNodeExecutionDTO toDto(AutomationNodeExecution row);
}
