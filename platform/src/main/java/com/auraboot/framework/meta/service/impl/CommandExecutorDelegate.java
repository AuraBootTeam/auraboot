package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;

import java.util.List;
import java.util.Map;

/**
 * Delegate interface exposing internal CommandExecutor operations for Phase implementations.
 * This allows phases to call existing logic during the incremental migration,
 * without directly depending on CommandExecutorImpl.
 *
 * @author AuraBoot Team
 * @since 8.0.0
 */
public interface CommandExecutorDelegate {





    void executeConsistencyCheckPhase(CommandDefinition command, Map<String, Object> payload,
                                       Map<String, Object> fieldMapResults, Long tenantId,
                                       Map<String, Object> execConfig);

    void executeRollUpRecalculation(String modelCode, Map<String, Object> payload,
                                     Map<String, Object> fieldMapResults, Long tenantId);

    void executeGovernanceSnapshot(String modelCode, Map<String, Object> payload,
                                    Map<String, Object> fieldMapResults, Long tenantId, Long userId);

    void executePostActionPhase(Map<String, Object> execConfig, Map<String, Object> payload,
                                 Long tenantId, Long userId, CommandDefinition command,
                                 CommandExecuteRequest request, Map<String, Object> fieldMapResults);

}
