package com.auraboot.framework.automation.service;

import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.dto.AutomationLogDTO;
import com.auraboot.framework.automation.dto.AutomationUpdateRequest;
import com.auraboot.framework.common.dto.PageResult;

import java.util.List;
import java.util.Map;

/**
 * Automation Service Interface
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface AutomationService {

    // ==================== CRUD Operations ====================

    /**
     * Create a new automation
     */
    AutomationDTO create(AutomationCreateRequest request);

    /**
     * Get automation by PID
     */
    AutomationDTO findByPid(String pid);

    /**
     * Update an existing automation
     */
    AutomationDTO update(String pid, AutomationUpdateRequest request);

    /**
     * Delete an automation (soft delete)
     */
    void delete(String pid);

    // ==================== Listing ====================

    /**
     * Get all automations for a model
     */
    List<AutomationDTO> getByModelCode(String modelCode);

    /**
     * Get enabled automations for a model
     */
    List<AutomationDTO> getEnabledByModelCode(String modelCode);

    /**
     * Search automations with pagination
     */
    PageResult<AutomationDTO> search(
            String keyword,
            String modelCode,
            String triggerType,
            Boolean enabled,
            int page,
            int size);

    // ==================== Enable/Disable ====================

    /**
     * Enable an automation
     */
    AutomationDTO enable(String pid);

    /**
     * Disable an automation
     */
    AutomationDTO disable(String pid);

    // ==================== Execution Logs ====================

    /**
     * Get execution logs for an automation
     */
    List<AutomationLogDTO> getLogs(String automationId, int limit);

    /**
     * Get execution log by PID
     */
    AutomationLogDTO getLogByPid(String logPid);

    /**
     * Get recent failed logs
     */
    List<AutomationLogDTO> getRecentFailedLogs(int limit);

    /**
     * Cleanup old logs (admin operation)
     */
    int cleanupOldLogs(int daysToKeep);

    // ==================== Toggle / Duplicate / Validate ====================

    /**
     * Toggle automation enabled state
     */
    AutomationDTO toggle(String pid);

    /**
     * Duplicate an automation
     */
    AutomationDTO duplicate(String pid);

    /**
     * Validate automation configuration without saving
     */
    Map<String, Object> validate(AutomationCreateRequest request);

    // ==================== Manual Trigger ====================

    /**
     * Manually trigger an automation for testing
     */
    AutomationLogDTO triggerManually(String pid, String recordId);
}
